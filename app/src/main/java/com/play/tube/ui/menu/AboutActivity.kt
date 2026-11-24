package com.play.tube.ui.menu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.play.tube.BuildConfig
import com.play.tube.R
import com.play.tube.databinding.ActivityAboutBinding
import com.play.tube.util.Secrets
import com.play.tube.util.Themes

/**
 * About page: GPL notice, no-warranty statement, SparkTube / NewPipe credits,
 * the modification notice, third-party licenses and a link to the full source.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.aboutBack.setOnClickListener { finish() }
        binding.aboutVersion.text = getString(
            R.string.dev_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
        binding.aboutModifiedText.text = getString(R.string.about_page_modified, Secrets.devName())
        binding.aboutPrivacyText.text = getString(R.string.about_privacy_text, Secrets.telegramHandle())
        binding.aboutSource.setOnClickListener {
            if (SOURCE_URL.contains("YOUR-USERNAME")) {
                Toast.makeText(this, R.string.about_source_not_set, Toast.LENGTH_SHORT).show()
            } else {
                open(SOURCE_URL)
            }
        }
        binding.aboutPrivacyBtn.setOnClickListener { open(PRIVACY_URL) }
        binding.aboutLicense.setOnClickListener { open(LICENSE_URL) }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onResume() {
        super.onResume()
        Themes.recreateIfNeeded(this)
    }

    companion object {
        /** Public repository that holds the complete source of the distributed build. */
        const val SOURCE_URL = "https://github.com/CyberPuIse/Play-Tube"
        private const val PRIVACY_URL = "https://github.com/CyberPuIse/Play-Tube/blob/main/PRIVACY.md"
        private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

        fun start(context: Context) {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }
    }
}
