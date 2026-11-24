package com.play.tube.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.play.tube.R
import com.play.tube.databinding.ActivityTamperBinding
import com.play.tube.util.Secrets

/**
 * Shown instead of the normal app when [com.play.tube.util.SignatureGuard]
 * finds that this APK is not signed with PlayTube's own release key — i.e.
 * someone repackaged/re-signed a modified copy. The only way forward is the
 * official latest APK from GitHub Releases, or closing the app.
 */
class TamperActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityTamperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tamperOpenOfficial.setOnClickListener {
            // Straight to the newest official APK on GitHub Releases: the browser
            // starts the download right away, no repo page in between.
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Secrets.latestApkUrl())))
            }
        }
        binding.tamperClose.setOnClickListener { finishAffinity() }
    }

    override fun onBackPressed() {
        finishAffinity()
    }

    companion object {
        fun start(context: android.content.Context) {
            val intent = Intent(context, TamperActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}
