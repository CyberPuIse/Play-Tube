package com.play.tube.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-made folders for the Downloads tab. A download (by videoId) belongs to
 * at most one folder; the files themselves never move. Stored locally.
 */
object DownloadFolders {

    private const val PREFS = "download_folders"
    private const val KEY_FOLDERS = "folders"
    private const val KEY_MAP = "map"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folders(context: Context): List<String> = try {
        val arr = JSONArray(sp(context).getString(KEY_FOLDERS, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun folderOf(context: Context, videoId: String): String? = try {
        val obj = JSONObject(sp(context).getString(KEY_MAP, "{}"))
        if (obj.has(videoId)) obj.getString(videoId) else null
    } catch (e: Exception) {
        null
    }

    /** @return false when the name is blank or already exists. */
    fun create(context: Context, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val current = folders(context)
        if (current.any { it.equals(clean, ignoreCase = true) }) return false
        saveFolders(context, current + clean)
        return true
    }

    /** Puts a download into [folder], or takes it out of any folder when [folder] is null. */
    fun assign(context: Context, videoId: String, folder: String?) {
        val obj = try {
            JSONObject(sp(context).getString(KEY_MAP, "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        if (folder == null) obj.remove(videoId) else obj.put(videoId, folder)
        sp(context).edit().putString(KEY_MAP, obj.toString()).apply()
    }

    /** Deletes the folder only; the downloads inside it stay on the device. */
    fun delete(context: Context, name: String) {
        saveFolders(context, folders(context).filterNot { it == name })
        val obj = try {
            JSONObject(sp(context).getString(KEY_MAP, "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        val keys = obj.keys().asSequence().toList()
        keys.filter { obj.optString(it) == name }.forEach { obj.remove(it) }
        sp(context).edit().putString(KEY_MAP, obj.toString()).apply()
    }

    private fun saveFolders(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp(context).edit().putString(KEY_FOLDERS, arr.toString()).apply()
    }
}
