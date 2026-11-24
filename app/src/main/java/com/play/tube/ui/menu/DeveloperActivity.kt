package com.play.tube.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.play.tube.BuildConfig
import com.play.tube.R
import com.play.tube.databinding.ActivityDeveloperBinding
import com.play.tube.util.Secrets
import com.play.tube.util.Themes

/** "Developer info" page opened from the Menu: who built PlayTube + contact. */
class DeveloperActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeveloperBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityDeveloperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.devBack.setOnClickListener { finish() }
        binding.devVersion.text = getString(
            R.string.dev_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )

        // Name and Telegram handle are decoded at runtime (util/Secrets.kt)
        // instead of sitting as plain text in strings.xml.
        binding.devNameText.text = Secrets.devName()
        binding.devAboutText.text = getString(R.string.dev_about_text, Secrets.devName())
        binding.devTelegramHandleText.text = Secrets.telegramHandle()

        binding.devTelegramRow.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Secrets.telegramUrl())))
            }
        }
        binding.devTelegramRow.setOnLongClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("username", Secrets.telegramHandle()))
            Toast.makeText(this, R.string.dev_copied, Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        Themes.recreateIfNeeded(this)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DeveloperActivity::class.java))
        }
    }
}
