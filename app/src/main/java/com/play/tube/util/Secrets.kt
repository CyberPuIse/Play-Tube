package com.play.tube.util

import android.util.Base64

/**
 * Developer name, Telegram handle/links and the official download link are
 * kept here as Base64 text instead of plain strings in strings.xml, so a quick
 * text search of the APK resources doesn't show them and they can't be changed
 * with a simple find-and-replace on resource files.
 *
 * Honesty note: this is obfuscation, not encryption. Base64 is trivially
 * reversible (that is exactly what [d] does). It stops the casual case only;
 * a determined person who decompiles the app can still read these values.
 */
object Secrets {

    private fun d(b64: String): String =
        String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)

    // "Hridoy Hasan Mishor"
    private const val NAME_B64 = "SHJpZG95IEhhc2FuIE1pc2hvcg=="

    // "@YellowlFlash"
    private const val TG_HANDLE_B64 = "QFllbGxvd2xGbGFzaA=="

    // "https://t.me/+XIFaiUU3_rhhMTk1"  (PlayTube Telegram channel invite link)
    private const val TG_URL_B64 = "aHR0cHM6Ly90Lm1lLytYSUZhaVVVM19yaGhNVGsx"

    // Always points at the newest public release's APK (fixed file name).
    private const val LATEST_APK_B64 =
        "aHR0cHM6Ly9naXRodWIuY29tL0N5YmVyUHVJc2UvUGxheS1UdWJlL3JlbGVhc2VzL2xhdGVzdC9kb3dubG9hZC9QbGF5VHViZS5hcGs="

    fun devName(): String = d(NAME_B64)
    fun telegramHandle(): String = d(TG_HANDLE_B64)
    fun telegramUrl(): String = d(TG_URL_B64)
    fun latestApkUrl(): String = d(LATEST_APK_B64)
}
