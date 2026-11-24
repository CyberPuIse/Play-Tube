package com.play.tube.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Checks, at startup, that this APK is still signed with PlayTube's own
 * release key (app/playtube.keystore in the private build repo) — the same
 * key every official PlayTube build and every official update uses.
 *
 * Why: anyone can unzip an APK, change strings/resources or even small bits
 * of code, and re-sign it with their OWN key, then share that modified build
 * as if it were PlayTube. Android lets that install fine (it's a normal,
 * validly-signed APK) — it just won't be able to auto-update from the real
 * PlayTube releases afterwards, which is easy for a casual user to miss.
 * This check catches that case explicitly and tells the person plainly.
 *
 * What this does NOT do: stop a skilled reverse engineer. Someone who
 * decompiles the app can also find and remove this very check, the same way
 * they could change any other code. Real protection comes from code
 * shrinking/obfuscation (R8, enabled for release builds) making that harder
 * and more time-consuming to find — this check is a cheap extra tripwire on
 * top of that, not a replacement for it.
 */
object SignatureGuard {

    /**
     * SHA-256 of the signing certificate baked into every official release —
     * app/playtube.keystore, alias "playtube". Regenerate this value (see
     * `keytool -list -v -keystore playtube.keystore`, the "SHA256:" line,
     * lowercase, no colons) only if that keystore is ever intentionally
     * replaced.
     */
    private const val EXPECTED_SHA256 =
        "356838be7ab9ec29de3c089bf981a98d01f5d32226af889d64838d1f8e378c3b"

    /** True when the running APK's signing certificate matches [EXPECTED_SHA256]. */
    fun isOfficial(context: Context): Boolean {
        val actual = currentSignatureSha256(context) ?: return false
        return actual.equals(EXPECTED_SHA256, ignoreCase = true)
    }

    private fun currentSignatureSha256(context: Context): String? = try {
        val pm = context.packageManager
        val signatures: Array<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signingInfo = info.signingInfo
            if (signingInfo != null && !signingInfo.hasMultipleSigners()) {
                signingInfo.signingCertificateHistory?.map { it.toByteArray() }?.toTypedArray()
                    ?: emptyArray()
            } else {
                emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }?.toTypedArray() ?: emptyArray()
        }
        val cert = signatures.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert)
        digest.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }
}
