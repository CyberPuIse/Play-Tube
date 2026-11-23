package com.play.tube.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.play.tube.R
import com.play.tube.download.DownloadCenter
import com.play.tube.util.AppPrefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * SponsorBlock support: asks the community database (sponsor.ajay.app) which
 * parts of the current video are sponsor reads, self-promotion, interaction
 * reminders (and optionally intros / outros) and jumps over them during
 * playback. Only the first 4 hex characters of the video id's SHA-256 are
 * sent, so the server never learns exactly what is being watched.
 *
 * A segment is skipped once per video: if the viewer seeks back into it, it
 * plays normally (that is the "undo").
 */
object SponsorBlock {

    private data class Segment(val startMs: Long, val endMs: Long, val category: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var activeUrl: String? = null
    @Volatile private var segments: List<Segment> = emptyList()
    private val skipped = HashSet<Int>()
    private var appContext: Context? = null
    private var ticking = false

    /** Called when a new video starts resolving. Safe to call repeatedly. */
    fun start(context: Context, videoUrl: String) {
        appContext = context.applicationContext
        activeUrl = videoUrl
        segments = emptyList()
        skipped.clear()
        if (!AppPrefs.sponsorBlock && !AppPrefs.sponsorBlockIntro) return
        val videoId = DownloadCenter.videoIdOf(videoUrl)
        if (videoId.length < 6) return
        Thread {
            val loaded = runCatching { fetch(videoId) }.getOrDefault(emptyList())
            handler.post {
                // Ignore a late answer for a video that is no longer current.
                if (activeUrl == videoUrl) {
                    segments = loaded
                    if (loaded.isNotEmpty()) ensureTicking()
                }
            }
        }.start()
    }

    fun stop() {
        activeUrl = null
        segments = emptyList()
        skipped.clear()
        handler.removeCallbacks(tick)
        ticking = false
    }

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            val url = activeUrl
            val list = segments
            if (url == null || list.isEmpty() || PlaybackCenter.mode != PlaybackCenter.Mode.VIDEO ||
                PlaybackCenter.currentEntry?.url != url
            ) {
                ticking = false
                return
            }
            val player = PlaybackCenter.player
            if (player.isPlaying) {
                val pos = player.currentPosition
                for (i in list.indices) {
                    val seg = list[i]
                    // Skip only when playback actually entered the segment from its
                    // start side, with a little slack before the end.
                    if (i !in skipped && pos >= seg.startMs && pos < seg.endMs - 400L) {
                        skipped += i
                        val duration = player.duration
                        val target = if (duration > 0) minOf(seg.endMs, duration) else seg.endMs
                        player.seekTo(target)
                        announce(seg.category)
                        break
                    }
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun announce(category: String) {
        val ctx = appContext ?: return
        val res = when (category) {
            "sponsor" -> R.string.sb_skipped_sponsor
            "selfpromo" -> R.string.sb_skipped_promo
            "interaction" -> R.string.sb_skipped_interaction
            "intro" -> R.string.sb_skipped_intro
            "outro" -> R.string.sb_skipped_outro
            else -> R.string.sb_skipped_sponsor
        }
        Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()
    }

    private fun wantedCategories(): List<String> {
        val out = ArrayList<String>()
        if (AppPrefs.sponsorBlock) out += listOf("sponsor", "selfpromo", "interaction")
        if (AppPrefs.sponsorBlockIntro) out += listOf("intro", "outro")
        return out
    }

    private fun fetch(videoId: String): List<Segment> {
        val categories = wantedCategories()
        if (categories.isEmpty()) return emptyList()
        val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
        val prefix = digest.take(2).joinToString("") { "%02x".format(it) }
        val cats = JSONArray(categories).toString()
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("sponsor.ajay.app")
            .addPathSegments("api/skipSegments/$prefix")
            .addQueryParameter("categories", cats)
            .addQueryParameter("actionType", "skip")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "PlayTube").build()
        client.newCall(request).execute().use { response ->
            // 404 = no segments known for any video with this hash prefix.
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            val videos = JSONArray(body)
            val result = ArrayList<Segment>()
            for (v in 0 until videos.length()) {
                val video = videos.getJSONObject(v)
                if (video.optString("videoID") != videoId) continue
                val segs = video.optJSONArray("segments") ?: continue
                for (s in 0 until segs.length()) {
                    val seg = segs.getJSONObject(s)
                    if (seg.optString("actionType", "skip") != "skip") continue
                    val range = seg.optJSONArray("segment") ?: continue
                    if (range.length() < 2) continue
                    val start = (range.getDouble(0) * 1000).toLong()
                    val end = (range.getDouble(1) * 1000).toLong()
                    // Skip junk and the tiny entries that are not worth a jump.
                    if (end - start < 1500L) continue
                    result += Segment(start, end, seg.optString("category"))
                }
            }
            return result.sortedBy { it.startMs }
        }
    }

    private const val TICK_MS = 400L
}
