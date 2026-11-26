package com.play.tube.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.play.tube.R
import com.play.tube.data.DownloadRecord
import com.play.tube.databinding.ItemDownloadBinding
import com.play.tube.download.DownloadCenter
import com.play.tube.download.DownloadSharing
import com.play.tube.util.AppPrefs
import com.play.tube.util.Thumbs

/** Download entries on the Library > Downloads tab. */
class DownloadAdapter(
    private val onClick: (DownloadRecord) -> Unit,
    private val onLongClick: (DownloadRecord) -> Unit,
    private val onPause: ((DownloadRecord) -> Unit)? = null,
    private val audioStyle: Boolean = false,
    private val onMove: ((DownloadRecord) -> Unit)? = null,
    private val onDelete: ((DownloadRecord) -> Unit)? = null
) : ListAdapter<DownloadRecord, DownloadAdapter.VH>(DIFF) {
    companion object {
        private val LIVE = Any()
        private val DIFF = object : DiffUtil.ItemCallback<DownloadRecord>() {
            override fun areItemsTheSame(a: DownloadRecord, b: DownloadRecord): Boolean =
                a.videoId == b.videoId && a.type == b.type && a.quality == b.quality
            override fun areContentsTheSame(a: DownloadRecord, b: DownloadRecord): Boolean =
                a.status == b.status && a.title == b.title && a.filePaths == b.filePaths &&
                    a.failReason == b.failReason
        }
    }

    class VH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    /** Runtime metrics are not persisted in DownloadRecord or compared by DiffUtil. */
    fun refreshLiveMetrics() {
        // Only rows that are actually transferring, and with a payload so the
        // row is NOT rebound from scratch (that caused the 1-second blink).
        for (i in 0 until itemCount) {
            val st = getItem(i).status
            if (st == DownloadCenter.STATUS_PENDING || st == DownloadCenter.STATUS_RUNNING || st == DownloadCenter.STATUS_PAUSED) {
                notifyItemChanged(i, LIVE)
            }
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === LIVE }) {
            bindLive(holder.binding, getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = b.root.context
        if (audioStyle) {
            val density = context.resources.displayMetrics.density
            val imageSize = (60 * density).toInt()
            b.thumb.layoutParams = b.thumb.layoutParams.apply {
                width = imageSize
                height = imageSize
            }
            b.title.maxLines = 1
            b.title.textSize = 15f
            b.meta.maxLines = 1
            b.root.setPadding((8 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
        }
        if (!audioStyle) {
            // YouTube-style: videos get a wide 16:9 thumbnail, music a small square cover.
            val d = context.resources.displayMetrics.density
            val isMusic = item.type == DownloadCenter.TYPE_AUDIO
            val w = ((if (isMusic) 56 else 136) * d).toInt()
            val h = ((if (isMusic) 56 else 76) * d).toInt()
            if (b.thumb.layoutParams.width != w || b.thumb.layoutParams.height != h) {
                b.thumb.layoutParams = b.thumb.layoutParams.apply { width = w; height = h }
            }
            b.title.maxLines = if (isMusic) 1 else 2
        }
        b.title.text = item.title
        val typeLabel = when (item.type) {
            DownloadCenter.TYPE_AUDIO -> context.getString(R.string.download_type_audio)
            DownloadCenter.TYPE_AV -> context.getString(R.string.download_type_av)
            else -> context.getString(R.string.download_type_video)
        }
        val statusLabel = when (item.status) {
            DownloadCenter.STATUS_DONE -> context.getString(R.string.download_status_done)
            DownloadCenter.STATUS_RUNNING -> context.getString(R.string.download_status_running)
            DownloadCenter.STATUS_FAILED -> context.getString(R.string.download_status_failed)
            DownloadCenter.STATUS_PAUSED -> context.getString(R.string.download_paused)
            else -> context.getString(R.string.download_status_pending)
        }
        b.meta.text = if (audioStyle) item.uploader.ifBlank { typeLabel }
            else "$typeLabel • ${item.quality} • $statusLabel"
        val active = item.status == DownloadCenter.STATUS_PENDING ||
            item.status == DownloadCenter.STATUS_RUNNING || item.status == DownloadCenter.STATUS_PAUSED
        val paused = item.status == DownloadCenter.STATUS_PAUSED
        b.pauseButton.visibility = if (active) View.VISIBLE else View.GONE
        b.pauseButton.text = context.getString(if (paused) R.string.download_resume else R.string.download_pause)
        b.pauseButton.setOnClickListener {
            onPause?.invoke(item) ?: DownloadCenter.togglePause(context, item)
        }

        bindLive(b, item)
        b.overflowButton.visibility = if (item.status == DownloadCenter.STATUS_DONE ||
            item.status == DownloadCenter.STATUS_FAILED) View.VISIBLE else View.GONE
        val openMenu = {
            val sheet = PremiumSheet(context).title(item.title)
            if (item.status == DownloadCenter.STATUS_DONE) {
                sheet.row(R.drawable.ic_play_arrow, context.getString(R.string.download_open)) { onClick(item) }
                    .row(R.drawable.ic_share, context.getString(R.string.download_share)) {
                        DownloadSharing.share(context, item)
                    }
                if (onMove != null) {
                    sheet.row(R.drawable.ic_folder, context.getString(R.string.folder_move)) { onMove.invoke(item) }
                }
            } else if (item.status == DownloadCenter.STATUS_FAILED) {
                sheet.row(R.drawable.ic_update, context.getString(R.string.download_retry)) {
                    if (!DownloadCenter.retry(context, item)) {
                        android.widget.Toast.makeText(
                            context, R.string.download_retry_open_video, android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            // Delete is kept apart from the other actions.
            sheet.row(R.drawable.ic_delete, context.getString(R.string.download_delete), destructive = true) {
                (onDelete ?: onLongClick).invoke(item)
            }.show()
        }
        b.overflowButton.setOnClickListener { openMenu() }
        Thumbs.load(b.thumb, item.thumbnailUrl)
        b.root.setOnClickListener { if (item.status == DownloadCenter.STATUS_DONE) onClick(item) else if (item.status == DownloadCenter.STATUS_FAILED) openMenu() }
        b.root.setOnLongClickListener { openMenu(); true }
    }

    /** Progress ring, bar and speed text: the only parts that change every second. */
    private fun bindLive(b: ItemDownloadBinding, item: DownloadRecord) {
        val context = b.root.context
        val active = item.status == DownloadCenter.STATUS_PENDING ||
            item.status == DownloadCenter.STATUS_RUNNING || item.status == DownloadCenter.STATUS_PAUSED
        val paused = item.status == DownloadCenter.STATUS_PAUSED
        val audioStyle = this.audioStyle
        val statusLabel = when (item.status) {
            DownloadCenter.STATUS_DONE -> context.getString(R.string.download_status_done)
            DownloadCenter.STATUS_RUNNING -> context.getString(R.string.download_status_running)
            DownloadCenter.STATUS_FAILED -> context.getString(R.string.download_status_failed)
            DownloadCenter.STATUS_PAUSED -> context.getString(R.string.download_paused)
            else -> context.getString(R.string.download_status_pending)
        }
        // Exactly one snapshot per bind; getters must not change the sampling period.
        val metrics = DownloadCenter.metrics(item)
        val total = metrics.totalBytes
        val downloaded = DownloadMetricFormat.megabytes(metrics.downloadedBytes)
        val sizeText = context.getString(R.string.download_size_progress, downloaded,
            total?.let(DownloadMetricFormat::megabytes) ?: context.getString(R.string.download_total_unknown))
        b.progress.visibility = if (active) View.VISIBLE else View.GONE
        // Circular ring over the thumbnail, like YouTube's download badge.
        b.thumbDim.visibility = if (active) View.VISIBLE else View.GONE
        b.thumbProgress.visibility = if (active) View.VISIBLE else View.GONE
        b.thumbProgress.isIndeterminate = active && !paused && total == null
        if (total != null && total > 0L) {
            b.thumbProgress.setProgressCompat(
                (metrics.downloadedBytes.toDouble() / total.toDouble() * 100.0).toInt().coerceIn(0, 100), true
            )
        }
        b.progress.isIndeterminate = active && !paused && total == null
        val permille = if (total != null && total > 0L) {
            (metrics.downloadedBytes.toDouble() / total.toDouble() * 1000.0).toInt().coerceIn(0, 1000)
        } else 0
        // Smoothly glide the long bar to its new value instead of jumping.
        (b.progress.getTag(R.id.progress) as? android.animation.ObjectAnimator)?.cancel()
        if (active && !b.progress.isIndeterminate && AppPrefs.animations) {
            val anim = android.animation.ObjectAnimator.ofInt(b.progress, "progress", b.progress.progress, permille)
            anim.duration = 900L
            anim.interpolator = android.view.animation.LinearInterpolator()
            b.progress.setTag(R.id.progress, anim)
            anim.start()
        } else {
            b.progress.progress = permille
        }

        val percentText = if (total != null && total > 0L) "${metrics.progressPercent}%  ·  " else ""
        when {
            paused -> {
                b.speed.text = percentText + sizeText
                b.speedRight.text = context.getString(R.string.download_paused)
            }
            active -> {
                val eta = metrics.remainingSeconds
                val timeText = when {
                    total == null -> context.getString(R.string.download_time_unknown)
                    eta != null -> context.getString(R.string.download_remaining, DownloadMetricFormat.duration(eta))
                    metrics.bytesPerSecond <= 0.0 && metrics.downloadedBytes > 0L -> context.getString(R.string.download_waiting)
                    else -> context.getString(R.string.download_estimating)
                }
                // Left: how much is done. Right: how fast / how long is left.
                b.speed.text = percentText + sizeText
                b.speedRight.text = "${DownloadMetricFormat.rate(metrics.bytesPerSecond)} · $timeText"
            }
            item.status == DownloadCenter.STATUS_DONE -> {
                b.speed.text = if (audioStyle) listOfNotNull(
                    item.quality.takeIf { it.isNotBlank() }, downloaded
                ).joinToString(" • ") else downloaded
                b.speedRight.text = ""
            }
            else -> {
                b.speed.text = if (audioStyle) statusLabel else ""
                b.speedRight.text = ""
            }
        }
        if (item.status == DownloadCenter.STATUS_FAILED) {
            b.speed.text = DownloadNotifierText.reason(context, item)
        }
    }
}

internal object DownloadNotifierText {
    fun reason(context: android.content.Context, item: DownloadRecord): String =
        if (item.failReason.isBlank()) "" else
            com.play.tube.download.DownloadNotifier.reasonText(context, item.failReason)
}
