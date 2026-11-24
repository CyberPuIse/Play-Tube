package com.play.tube.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.play.tube.databinding.ActivitySplashBinding
import com.play.tube.util.AppPrefs
import com.play.tube.util.Themes

/**
 * Launcher screen: a short YouTube-style intro (play button pops in with a red
 * glow burst, then the "PlayTube" wordmark slides together) followed by the
 * main screen. The region is fixed to Bangladesh, so there is no region picker.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Personal build: region is always Bangladesh unless something else was already stored.
        if (AppPrefs.country == null) AppPrefs.country = AppPrefs.DEFAULT_COUNTRY

        if (AppPrefs.animations) {
            playIntro()
            handler.postDelayed({ goNext() }, INTRO_TOTAL_MS)
        } else {
            handler.postDelayed({ goNext() }, 400L)
        }
    }

    private fun playIntro() {
        val b = binding
        val d = resources.displayMetrics.density
        val dec = DecelerateInterpolator(2f)
        val over = OvershootInterpolator(1.9f)

        // The shine must stay inside the rounded button.
        b.logoFrame.clipToOutline = true
        b.logoFrame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 28 * d)
            }
        }

        // Wordmark: one view per letter so they can rise in one after another.
        val letters = ArrayList<TextView>()
        fun addWord(word: String, color: Int) {
            word.forEach { ch ->
                val tv = TextView(this).apply {
                    text = ch.toString()
                    setTextColor(color)
                    textSize = 40f
                    typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.ITALIC)
                    alpha = 0f
                    translationY = 26 * d
                    scaleX = 0.7f; scaleY = 0.7f
                }
                b.wordRow.addView(tv)
                letters.add(tv)
            }
        }
        addWord(getString(com.play.tube.R.string.splash_word_play),
            androidx.core.content.ContextCompat.getColor(this, com.play.tube.R.color.on_surface))
        addWord(getString(com.play.tube.R.string.splash_word_tube),
            androidx.core.content.ContextCompat.getColor(this, com.play.tube.R.color.brand_red))

        b.logoFrame.apply { scaleX = 0f; scaleY = 0f; alpha = 0f }
        b.logoPlay.apply { scaleX = 0f; scaleY = 0f; rotation = -40f }
        b.glow.apply { alpha = 0f; scaleX = 0.3f; scaleY = 0.3f }
        b.tagline.apply { alpha = 0f; translationY = 10 * d }
        b.premiumBadge.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f }
        b.loadBar.scaleX = 0f
        b.ring1.apply { alpha = 0f; scaleX = 0.8f; scaleY = 0.8f }
        b.ring2.apply { alpha = 0f; scaleX = 0.8f; scaleY = 0.8f }
        b.logoShine.apply { alpha = 0f; rotation = 18f; translationX = -80 * d }

        // 1) Button grows out of nothing with a soft overshoot.
        b.logoFrame.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(620).setInterpolator(over).start()

        // 2) Play triangle spins in a beat later.
        b.logoPlay.animate().scaleX(1f).scaleY(1f).rotation(0f)
            .setStartDelay(300).setDuration(520).setInterpolator(over)
            .withEndAction { com.play.tube.util.Haptics.soft(b.logoFrame) }.start()

        // 3) Glow burst + two ripple rings leaving the button.
        b.glow.animate().alpha(1f).scaleX(1.15f).scaleY(1.15f).setStartDelay(420).setDuration(420)
            .withEndAction {
                b.glow.animate().alpha(0.35f).scaleX(1.5f).scaleY(1.5f).setStartDelay(0).setDuration(900).start()
            }.start()
        listOf(b.ring1 to 520L, b.ring2 to 800L).forEach { (ring, delay) ->
            ring.animate().alpha(0.8f).scaleX(1.1f).scaleY(1.1f).setStartDelay(delay).setDuration(120)
                .withEndAction {
                    ring.animate().alpha(0f).scaleX(3.6f).scaleY(3.6f).setStartDelay(0).setDuration(900)
                        .setInterpolator(DecelerateInterpolator(1.4f)).start()
                }.start()
        }

        // 4) Light sweep across the button.
        b.logoShine.animate().alpha(1f).translationX(150 * d).setStartDelay(780).setDuration(650)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { b.logoShine.alpha = 0f }.start()

        // 5) Letters rise in a staggered wave.
        letters.forEachIndexed { i, tv ->
            tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(760L + i * 55L).setDuration(460).setInterpolator(over).start()
        }

        // 6) Tagline, badge and progress pill settle in.
        b.tagline.animate().alpha(1f).translationY(0f).setStartDelay(1300).setDuration(450)
            .setInterpolator(dec).start()
        b.premiumBadge.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(1250).setDuration(380)
            .setInterpolator(OvershootInterpolator(2.4f)).start()
        b.loadTrack.animate().alpha(1f).setStartDelay(900).setDuration(300).start()
        b.loadBar.animate().scaleX(1f).setStartDelay(950).setDuration(INTRO_TOTAL_MS - 1250)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()

        // 7) Little "heartbeat" on the logo, then everything eases out into the app.
        b.logoFrame.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            b.logoFrame.animate().scaleX(1.07f).scaleY(1.07f).setStartDelay(0).setDuration(160)
                .withEndAction {
                    b.logoFrame.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
                }.start()
        }, 1750)
        b.logoButton.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            b.root.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(300)
                .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
        }, INTRO_TOTAL_MS - 320)
    }

    private fun goNext() {
        if (launched || isFinishing || isDestroyed) return
        launched = true
        startActivity(Intent(this,
            if (com.play.tube.util.AppPrefs.introSeen) MainActivity::class.java else IntroActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val INTRO_TOTAL_MS = 2800L
    }
}
