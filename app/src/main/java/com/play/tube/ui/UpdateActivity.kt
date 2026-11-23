package com.play.tube.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.play.tube.BuildConfig
import com.play.tube.R
import com.play.tube.databinding.ActivityUpdateBinding
import com.play.tube.util.Themes
import com.play.tube.util.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Full-screen updater. Shows the new version + changelog, downloads the APK
 * inside the app with a live progress bar (resuming a half-finished file),
 * then launches the system installer. When the update is mandatory the
 * screen cannot be dismissed with Back.
 */
class UpdateActivity : AppCompatActivity() {

    private enum class State { IDLE, DOWNLOADING, READY, ERROR }

    private lateinit var binding: ActivityUpdateBinding
    private lateinit var info: UpdateChecker.UpdateInfo
    private var state = State.IDLE
    private var downloadJob: Job? = null
    private var apkFile: File? = null
    private var waitingForInstallPermission = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        val pending = UpdateChecker.pending(this)
        if (pending == null) {
            finish()
            return
        }
        info = pending
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.updateTitle.setText(
            if (info.force) R.string.update_required_title else R.string.update_optional_title
        )
        binding.updateVersion.text = getString(
            R.string.update_version_fmt, BuildConfig.VERSION_NAME, info.versionName
        )
        binding.updateNotes.text = info.changelog.ifBlank { info.versionName }
        binding.updateNotesCard.isVisible = info.changelog.isNotBlank()
        binding.updateLater.isVisible = !info.force
        binding.updateLater.setOnClickListener { finish() }
        binding.updateButton.setOnClickListener { onButton() }

        // Mandatory update: Back must not leave the screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!info.force) finish()
            }
        })

        // An earlier run may already have finished the download.
        val done = finishedFile()
        if (done.isFile && done.length() > 0) {
            apkFile = done
            setState(State.READY)
        } else {
            setState(State.IDLE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        // Back from the "allow installs" settings page: continue installing.
        if (waitingForInstallPermission && canInstall()) {
            waitingForInstallPermission = false
            apkFile?.let { install(it) }
        }
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        super.onDestroy()
    }

    // ----- Files -----

    private fun finishedFile() = File(UpdateChecker.updatesDir(this), "PlayTube-${info.versionCode}.apk")
    private fun partFile() = File(UpdateChecker.updatesDir(this), "PlayTube-${info.versionCode}.apk.part")

    // ----- UI state -----

    private fun onButton() {
        when (state) {
            State.IDLE, State.ERROR -> startDownload()
            State.READY -> apkFile?.let { install(it) }
            State.DOWNLOADING -> Unit
        }
    }

    private fun setState(newState: State) {
        state = newState
        val b = binding
        b.updateProgressBlock.isVisible = newState == State.DOWNLOADING
        b.updateError.isVisible = newState == State.ERROR
        b.updateButton.isEnabled = newState != State.DOWNLOADING
        b.updateButton.alpha = if (newState == State.DOWNLOADING) 0.6f else 1f
        b.updateButton.setText(
            when (newState) {
                State.IDLE -> R.string.update_btn_update
                State.DOWNLOADING -> R.string.update_btn_downloading
                State.READY -> R.string.update_btn_install
                State.ERROR -> R.string.update_btn_retry
            }
        )
        // Changelog is hidden while the progress block needs the room.
        b.updateMessage.isVisible = newState != State.DOWNLOADING
    }

    private fun showProgress(done: Long, total: Long, bytesPerSec: Double) {
        val b = binding
        if (total > 0) {
            val fraction = (done.toDouble() / total).coerceIn(0.0, 1.0)
            b.updateBar.progress = (fraction * 1000).toInt()
            b.updatePercent.text = "${(fraction * 100).toInt()}%"
            b.updateSizeText.text = getString(
                R.string.update_progress_fmt,
                Formatter.formatShortFileSize(this, done),
                Formatter.formatShortFileSize(this, total)
            )
        } else {
            b.updateBar.progress = 0
            b.updatePercent.text = ""
            b.updateSizeText.text = getString(
                R.string.update_progress_unknown_fmt, Formatter.formatShortFileSize(this, done)
            )
        }
        b.updateSpeed.text = if (bytesPerSec > 0) {
            getString(
                R.string.update_speed_fmt,
                Formatter.formatShortFileSize(this, bytesPerSec.toLong())
            )
        } else ""
    }

    // ----- Download -----

    private fun startDownload() {
        downloadJob?.cancel()
        setState(State.DOWNLOADING)
        showProgress(0, 0, 0.0)
        downloadJob = lifecycleScope.launch {
            try {
                val file = download()
                apkFile = file
                setState(State.READY)
                install(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BadFileException) {
                fail(R.string.update_bad_file)
            } catch (e: Exception) {
                fail(R.string.update_failed)
            }
        }
    }

    private fun fail(message: Int) {
        binding.updateError.setText(message)
        setState(State.ERROR)
    }

    private class BadFileException : IOException()

    /** Downloads to a .part file (resuming if possible), verifies, and renames. */
    private suspend fun download(): File = withContext(Dispatchers.IO) {
        val part = partFile()
        val target = finishedFile()
        var existing = if (part.isFile) part.length() else 0L

        val builder = Request.Builder().url(info.apkUrl).header("User-Agent", "PlayTube")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 416) {
                // Our partial file is already complete or stale: start over.
                part.delete()
                throw IOException("range not satisfiable")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val resumed = response.code == 206 && existing > 0
            if (!resumed) existing = 0L
            val total = body.contentLength().let { if (it > 0) it + existing else -1L }

            var downloaded = existing
            var lastUi = 0L
            var sampleAt = System.nanoTime()
            var sampleBytes = downloaded
            var rate = 0.0
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(existing) else out.setLength(0)
                val buf = ByteArray(32 * 1024)
                body.byteStream().use { input ->
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.nanoTime()
                        if (now - lastUi > 150_000_000L) {
                            lastUi = now
                            val dt = (now - sampleAt) / 1e9
                            if (dt >= 0.5) {
                                val inst = (downloaded - sampleBytes) / dt
                                rate = if (rate == 0.0) inst else rate * 0.6 + inst * 0.4
                                sampleAt = now
                                sampleBytes = downloaded
                            }
                            val d = downloaded
                            val r = rate
                            withContext(Dispatchers.Main) { showProgress(d, total, r) }
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) throw IOException("incomplete")
        }

        if (info.sha256.isNotEmpty() && sha256Of(part) != info.sha256) {
            part.delete()
            throw BadFileException()
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("rename failed")
        target
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ----- Install -----

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun install(file: File) {
        if (!canInstall()) {
            // "Install unknown apps" must be allowed once for PlayTube itself.
            waitingForInstallPermission = true
            Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure {
            fail(R.string.update_failed)
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, UpdateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
