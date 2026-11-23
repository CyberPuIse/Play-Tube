package com.play.tube.util

import android.content.Context
import com.play.tube.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updater. The app reads a small JSON file you host online
 * ([CONFIG_URL]); when its "versionCode" is higher than the installed
 * BuildConfig.VERSION_CODE the update screen opens, downloads the APK inside
 * the app (with progress) and launches the installer.
 *
 * Unless the JSON says "force": false, the update is mandatory: the screen
 * cannot be dismissed and the app stays blocked until the new version is
 * installed. The last known requirement is cached, so going offline does not
 * bypass it.
 *
 * version.json example:
 * {
 *   "versionCode": 12,
 *   "versionName": "1.5.0",
 *   "apkUrl": "https://example.com/PlayTube-1.5.0.apk",
 *   "changelog": "New: SponsorBlock, better search",
 *   "force": true,
 *   "sha256": "optional lowercase hex checksum of the APK"
 * }
 */
object UpdateChecker {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val force: Boolean,
        val sha256: String
    ) {
        fun toJson(): String = JSONObject()
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("apkUrl", apkUrl)
            .put("changelog", changelog)
            .put("force", force)
            .put("sha256", sha256)
            .toString()

        companion object {
            fun fromJson(text: String): UpdateInfo? = try {
                val j = JSONObject(text)
                val code = j.optInt("versionCode", -1)
                val url = j.optString("apkUrl")
                if (code < 0 || url.isBlank()) null else UpdateInfo(
                    versionCode = code,
                    versionName = j.optString("versionName").ifBlank { code.toString() },
                    apkUrl = url,
                    changelog = j.optString("changelog"),
                    force = j.optBoolean("force", true),
                    sha256 = j.optString("sha256").trim().lowercase()
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Direct (raw) URL of your hosted version.json. Leave blank to switch the
     * updater off. It must be reachable without a login, e.g. a file on
     * GitHub Pages / Cloudflare Pages / Netlify or a public raw URL.
     */
    const val CONFIG_URL = "https://dry-paper-a39c.nixecs1.workers.dev/version.json"

    val isConfigured: Boolean get() = CONFIG_URL.isNotBlank()

    /**
     * True once this app "open" has been checked. Reset when every screen of
     * the app has been in the background, so each fresh open checks again.
     */
    @Volatile
    var checkedThisOpen = false

    private const val PREFS = "playtube_update"

    private const val KEY_INFO = "info"

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The cached info, whether or not it is newer than this install. */
    private fun cached(context: Context): UpdateInfo? =
        sp(context).getString(KEY_INFO, null)?.let(UpdateInfo::fromJson)

    /** True when [info] describes a build newer than the one that is running. */
    fun isNewer(info: UpdateInfo): Boolean = info.versionCode > BuildConfig.VERSION_CODE

    /** Cached pending update (no network); null when up to date. */
    fun pending(context: Context): UpdateInfo? = cached(context)?.takeIf(::isNewer)

    /** Folder where downloaded APKs are kept. */
    fun updatesDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "updates").also { it.mkdirs() }
    }

    /**
     * Asks the server for the latest version. On success the answer is cached
     * and returned when newer; on a network failure the cached answer is used.
     * @return the pending update, or null when up to date / disabled.
     */
    suspend fun refresh(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val fresh = try {
            val request = Request.Builder()
                .url(CONFIG_URL)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else UpdateInfo.fromJson(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null
        }
        if (fresh != null) {
            sp(context).edit().putString(KEY_INFO, fresh.toJson()).apply()
            if (!isNewer(fresh)) {
                // Up to date (or just updated): drop leftover installers.
                runCatching { updatesDir(context).listFiles()?.forEach { it.delete() } }
                return@withContext null
            }
            return@withContext fresh
        }
        pending(context)
    }
}
