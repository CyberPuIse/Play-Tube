package com.play.tube.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.play.tube.R
import com.play.tube.data.DownloadRecord
import com.play.tube.ui.MainActivity

/**
 * All download notifications in one place:
 *  - an ongoing progress notification (owned by [DownloadService]),
 *  - a "download complete" notification that opens the Downloads tab,
 *  - a "download failed" notification that says why and has a Retry button.
 * Posting is skipped silently when the person has notifications turned off.
 */
object DownloadNotifier {

    const val CHANNEL_PROGRESS = "playtube_downloads"
    const val CHANNEL_RESULT = "playtube_download_results"
    const val PROGRESS_ID = 7101

    private const val ACTION_RETRY = "com.play.tube.action.DOWNLOAD_RETRY"
    private const val EXTRA_VIDEO_ID = "video_id"
    private const val EXTRA_TYPE = "type"
    private const val EXTRA_QUALITY = "quality"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS, context.getString(R.string.notif_channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT, context.getString(R.string.notif_channel_downloads_done),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun openDownloadsIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        return PendingIntent.getActivity(
            context, 7102, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Builds the ongoing notification shown while the download service runs. */
    fun progress(context: Context, titles: List<String>, percent: Int?, speedText: String): android.app.Notification {
        ensureChannels(context)
        val title = if (titles.size <= 1) titles.firstOrNull() ?: context.getString(R.string.notif_downloading_one)
        else context.getString(R.string.notif_downloading_many, titles.size)
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(speedText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openDownloadsIntent(context))
        if (percent != null) b.setProgress(100, percent.coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun canPost(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun idFor(record: DownloadRecord) =
        8000 + ((record.videoId + record.type + record.quality).hashCode() and 0xFFFF)

    fun done(context: Context, record: DownloadRecord) {
        if (!canPost(context)) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_download_done_title))
            .setContentText(record.title)
            .setAutoCancel(true)
            .setContentIntent(openDownloadsIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(idFor(record), n) }
    }

    fun failed(context: Context, record: DownloadRecord, reason: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val retry = Intent(context, DownloadActionReceiver::class.java)
            .setAction(ACTION_RETRY)
            .putExtra(EXTRA_VIDEO_ID, record.videoId)
            .putExtra(EXTRA_TYPE, record.type)
            .putExtra(EXTRA_QUALITY, record.quality)
        val retryPending = PendingIntent.getBroadcast(
            context, idFor(record), retry, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_download_failed_title))
            .setContentText(record.title + " · " + reasonText(context, reason))
            .setStyle(NotificationCompat.BigTextStyle().bigText(record.title + "\n" + reasonText(context, reason)))
            .setAutoCancel(true)
            .setContentIntent(openDownloadsIntent(context))
            .addAction(0, context.getString(R.string.notif_retry), retryPending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(idFor(record), n) }
    }

    fun cancelFor(context: Context, record: DownloadRecord) {
        runCatching { NotificationManagerCompat.from(context).cancel(idFor(record)) }
    }

    fun reasonText(context: Context, reason: String): String = context.getString(
        when (reason) {
            DownloadCenter.REASON_NETWORK -> R.string.fail_network
            DownloadCenter.REASON_STORAGE -> R.string.fail_storage
            DownloadCenter.REASON_UNAVAILABLE -> R.string.fail_unavailable
            else -> R.string.fail_unknown
        }
    )

    /** Handles the Retry button: finds the record again and restarts it. */
    internal fun handleRetry(context: Context, intent: Intent) {
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val quality = intent.getStringExtra(EXTRA_QUALITY) ?: return
        val record = com.play.tube.data.LocalStore.downloads(context).firstOrNull {
            it.videoId == videoId && it.type == type && it.quality == quality
        } ?: return
        cancelFor(context, record)
        if (!DownloadCenter.retry(context, record)) {
            // Stream links are gone (app was restarted): open the app instead.
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            runCatching { context.startActivity(open) }
        }
    }
}
