package com.play.tube.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.play.tube.data.LocalStore

/**
 * Keeps downloads alive while the screen is off / app is in the background:
 * a foreground service (so the system doesn't kill the process) plus a
 * partial wake lock (so the CPU keeps running). It also drives the ongoing
 * progress notification and stops itself once nothing is downloading.
 */
class DownloadService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var idleTicks = 0

    private val tick = object : Runnable {
        override fun run() {
            val active = runCatching {
                DownloadCenter.refreshStatuses(applicationContext)
                LocalStore.downloads(applicationContext).filter {
                    it.status == DownloadCenter.STATUS_PENDING || it.status == DownloadCenter.STATUS_RUNNING
                }
            }.getOrDefault(emptyList())

            if (active.isEmpty()) {
                // Paused-only or finished: give a short grace period, then stop.
                if (++idleTicks >= 3) { stopSelf(); return }
            } else {
                idleTicks = 0
                val metrics = active.map { DownloadCenter.metrics(it) }
                val speed = metrics.sumOf { it.bytesPerSecond }
                val percent = if (active.size == 1) metrics[0].progressPercent.takeIf { metrics[0].totalBytes != null } else null
                val text = (if (percent != null) "$percent%  ·  " else "") + formatSpeed(speed)
                val n = DownloadNotifier.progress(applicationContext, active.map { it.title }, percent, text)
                runCatching {
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(DownloadNotifier.PROGRESS_ID, n)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun formatSpeed(bps: Double): String {
        val mb = bps / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format("%.1f MB/s", mb) else String.format("%.0f KB/s", bps / 1024.0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = DownloadNotifier.progress(applicationContext, emptyList(), null, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(DownloadNotifier.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(DownloadNotifier.PROGRESS_ID, n)
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlayTube:download").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        }
        idleTicks = 0
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun ensureRunning(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext, Intent(context.applicationContext, DownloadService::class.java)
                )
            }
        }
    }
}
