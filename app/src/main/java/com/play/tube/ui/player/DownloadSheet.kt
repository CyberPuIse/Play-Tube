package com.play.tube.ui.player

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.play.tube.R
import com.play.tube.download.DownloadCenter
import com.play.tube.playback.AudioTrackGroup
import com.play.tube.playback.QueueEntry
import com.play.tube.playback.StreamCatalog
import com.play.tube.playback.effectiveHeight
import com.play.tube.util.Themes

/**
 * Download picker: Audio only / Video only / Video + Audio, each with a
 * quality list. On the music player only the audio section is shown.
 * Options that were already downloaded appear marked and can't be started
 * again.
 */
class DownloadSheet(
    context: Context,
    private val entry: QueueEntry,
    private val catalog: StreamCatalog,
    private val audioOnly: Boolean = false
) : BottomSheetDialog(context) {

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    /** Whether this exact type + quality was already downloaded successfully. */
    private fun downloaded(type: String, quality: String): Boolean =
        DownloadCenter.recordsFor(context, entry.url).any {
            it.type == type && it.quality == quality && it.status == DownloadCenter.STATUS_DONE
        }

    override fun show() {
        if (com.play.tube.util.AppPrefs.wifiOnlyDownloads && !com.play.tube.util.Net.isOnWifi(context)) {
            android.widget.Toast.makeText(
                context, R.string.download_wifi_only_blocked, android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val scroll = android.widget.ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.action_download)
            textSize = 18f
            setTextColor(context.getColor(R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(title)
        val songTitle = TextView(context).apply {
            text = entry.title
            textSize = 13f
            maxLines = 1
            setTextColor(context.getColor(R.color.on_surface_variant))
        }
        root.addView(songTitle)

        val musicOnly = audioOnly || !catalog.hasVideo
        val durationSec = catalog.durationSec.takeIf { it > 0 } ?: entry.durationSec

        // ----- Audio: only Low / Medium / High -----
        val audioTrack = catalog.audioTracks.firstOrNull()
        if (audioTrack != null) {
            root.addView(sectionTitle(context.getString(R.string.dl_audio_title)))
            val buckets = audioTrack.streams.filter { it.isUrl }.groupBy { bucketOf(it.averageBitrate) }
            listOf(Q_HIGH, Q_MEDIUM, Q_LOW).forEach { bucket ->
                val stream = buckets[bucket]?.maxByOrNull { it.averageBitrate } ?: return@forEach
                val label = context.getString(
                    when (bucket) { Q_HIGH -> R.string.dl_quality_high; Q_MEDIUM -> R.string.dl_quality_medium; else -> R.string.dl_quality_low }
                )
                val size = sizeOf(stream.itagItem?.contentLength ?: -1L, stream.averageBitrate, durationSec)
                addOption(root, label, size, DownloadCenter.TYPE_AUDIO, label, listOf(stream.content)) {
                    DownloadCenter.startSingle(
                        context, entry.url, entry.title, entry.uploader, entry.thumbnailUrl,
                        DownloadCenter.TYPE_AUDIO, label, stream.content, "m4a"
                    )
                }
            }
        }

        if (!musicOnly) {
            // ----- Video (with sound), 144p..1080p only -----
            root.addView(sectionTitle(context.getString(R.string.dl_video_title)))
            val pairing = catalog.videoOnly.isNotEmpty() && audioTrack?.best != null
            val choices = (if (pairing) catalog.videoOnly else catalog.muxed)
                .filter { it.effectiveHeight() in 144..1080 }
            choices.forEach { v ->
                val quality = "${v.effectiveHeight()}p"
                var size = sizeOf(v.itagItem?.contentLength ?: -1L, v.bitrate, durationSec)
                if (pairing) {
                    val a = audioTrack!!.best!!
                    val asz = sizeOf(a.itagItem?.contentLength ?: -1L, a.averageBitrate, durationSec)
                    size = if (size > 0 && asz > 0) size + asz else size
                }
                val type = if (pairing) DownloadCenter.TYPE_AV else DownloadCenter.TYPE_VIDEO
                addOption(root, quality, size, type, quality,
                    if (pairing) listOf(v.content, audioTrack!!.best!!.content) else listOf(v.content)) {
                    if (pairing) {
                        DownloadCenter.startPair(
                            context, entry.url, entry.title, entry.uploader, entry.thumbnailUrl,
                            quality, v.content, audioTrack!!.best!!.content
                        )
                    } else {
                        DownloadCenter.startSingle(
                            context, entry.url, entry.title, entry.uploader, entry.thumbnailUrl,
                            DownloadCenter.TYPE_VIDEO, quality, v.content, "mp4"
                        )
                    }
                }
            }
        }

        scroll.addView(root)
        setContentView(scroll)
        super.show()
    }


    private val Q_LOW = 0
    private val Q_MEDIUM = 1
    private val Q_HIGH = 2

    /** kbps -> Low (<=64) / Medium (<=160) / High (above). Unknown counts as Medium. */
    private fun bucketOf(kbps: Int): Int = when {
        kbps <= 0 -> Q_MEDIUM
        kbps <= 64 -> Q_LOW
        kbps <= 160 -> Q_MEDIUM
        else -> Q_HIGH
    }

    /** Real content length when known, else bitrate x duration. 0 = unknown. */
    private fun sizeOf(contentLength: Long, kbps: Int, durationSec: Long): Long = when {
        contentLength > 0 -> contentLength
        kbps > 0 && durationSec > 0 -> kbps * 1000L / 8 * durationSec
        else -> 0L
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb.coerceAtLeast(1.0))
    }

    /** One row: label + size on the right. Warns on duplicates and low storage. */
    private fun addOption(root: LinearLayout, label: String, size0: Long, type: String, quality: String, urls: List<String>, start: () -> Unit) {
        var size = size0
        val existing = DownloadCenter.recordsFor(context, entry.url)
            .firstOrNull { it.type == type && it.quality == quality && it.status == DownloadCenter.STATUS_DONE }
        fun textFor(sz: Long, exact: Boolean) = buildString {
            append(label)
            if (sz > 0) append("  ·  ").append(if (exact) formatSize(sz) else context.getString(R.string.dl_size_approx, formatSize(sz)))
            if (existing != null) append("  ·  ").append(context.getString(R.string.download_option_done))
        }
        val row = optionRow(textFor(0L, true), existing != null) {
            val go = {
                if (existing != null) DownloadCenter.delete(context, existing)
                if (size > 0 && DownloadCenter.freeBytes(context) < size + size / 10) {
                    // Warn only; the download still starts.
                    Toast.makeText(
                        context,
                        context.getString(R.string.dl_storage_warning, formatSize(DownloadCenter.freeBytes(context)), formatSize(size)),
                        Toast.LENGTH_LONG
                    ).show()
                }
                start()
                toastStarted()
                dismiss()
            }
            if (existing != null) {
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.dl_duplicate_title)
                    .setMessage(R.string.dl_duplicate_msg)
                    .setPositiveButton(R.string.dl_duplicate_again) { _, _ -> go() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else go()
        }
        root.addView(row)
        // Replace the estimate with the real size reported by the server.
        Thread {
            val exact = urls.map { exactLength(it) }
            if (exact.isNotEmpty() && exact.all { it > 0 }) {
                val total = exact.sum()
                row.post { size = total; row.text = textFor(total, true) }
            }
        }.start()
    }

    /** Real byte length of a stream URL (1-byte range request), or -1. */
    private fun exactLength(url: String): Long = runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(
            okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").build()
        ).execute().use { r ->
            r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 0 } ?: -1L
        }
    }.getOrDefault(-1L)

    private fun sectionTitle(label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(context.getColor(R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
            layoutParams = marginParams(top = 18)
        }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(top) }

    private fun optionRow(label: String, done: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(
                if (done) Themes.onSurfaceVariantColor(context)
                else Themes.onSurfaceColor(context)
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Themes.elevatedColor(context))
            }
            layoutParams = marginParams(top = 8)
            setOnClickListener { onClick() }
        }

    private fun suffix(track: AudioTrackGroup): String {
        return if (track.label.isNotBlank() && track.label != "Default") {
            " • ${track.label}"
        } else {
            ""
        }
    }

    private fun toastStarted() {
        com.play.tube.ui.common.DownloadToast.showStarted(context)
    }
}
