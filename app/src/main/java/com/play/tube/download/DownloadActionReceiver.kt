package com.play.tube.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the Retry button of a "download failed" notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { DownloadNotifier.handleRetry(context.applicationContext, intent) }
    }
}
