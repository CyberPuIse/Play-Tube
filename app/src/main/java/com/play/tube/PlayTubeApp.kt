package com.play.tube

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.play.tube.net.OkHttpDownloader
import com.play.tube.playback.PlaybackCenter
import com.play.tube.util.AppPrefs
import com.play.tube.util.CrashReporter
import com.play.tube.util.SignatureGuard
import com.play.tube.util.Themes
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class PlayTubeApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // Refuse to run a copy of PlayTube that was re-signed with a
        // different key (i.e. someone edited/repackaged it). See
        // util/SignatureGuard.kt for exactly what this does and doesn't catch.
        if (!SignatureGuard.isOfficial(this)) {
            com.play.tube.ui.TamperActivity.start(this)
            return
        }

        // Local crash reporting first — installed before anything else so a
        // crash during init (prefs, players, extractor) is still logged.
        //
        // This replaces Firebase Crashlytics: google-services.json (which
        // contains the Firebase web API key) used to live in this open-source
        // repo, so GitHub secret scanning flagged the key on every push — an
        // API key in public source can never be kept secret. Crash logs are
        // now written to a "PlayTube" folder on the user's device instead;
        // see util/CrashReporter.kt and Settings → Crash logs.
        CrashReporter.install(this)

        AppPrefs.init(this)
        PlaybackCenter.init(this)
        watchAppVisibility()
        Themes.applyDefaultNightMode()

        CrashReporter.logBreadcrumb(
            "PlayTube ${BuildConfig.VERSION_NAME} started"
        )

        NewPipe.init(
            OkHttpDownloader,
            Localization("en", "US"),
            ContentCountry(AppPrefs.countryOrDefault)
        )
        // Downloads now run through the app's own multi-thread engine
        // (DownloadCenter), so there is no system DownloadManager broadcast
        // to listen for anymore.
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()

    /**
     * Tells the player when no screen of the app is visible, so it can stop
     * downloading the picture of a video that nobody is looking at. A short
     * delay avoids flicker while moving between our own screens.
     */
    private fun watchAppVisibility() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var started = 0
        val hide = Runnable {
            PlaybackCenter.setAppVisible(false)
            // The app was really left: the next open checks for updates again.
            com.play.tube.util.UpdateChecker.checkedThisOpen = false
        }
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                started++
                handler.removeCallbacks(hide)
                PlaybackCenter.setAppVisible(true)
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) handler.postDelayed(hide, 2000L)
            }

            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, s: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}
