package com.play.tube.ui.menu

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.play.tube.BuildConfig
import com.play.tube.R
import com.play.tube.data.Countries
import com.play.tube.data.YtRepository
import com.play.tube.databinding.FragmentMenuBinding
import com.play.tube.util.AppPrefs
import com.play.tube.util.BackupManager
import com.play.tube.util.Themes
import com.play.tube.util.UpdateChecker
import kotlinx.coroutines.launch

class MenuFragment : Fragment() {

    private var _binding: FragmentMenuBinding? = null
    private val binding get() = _binding!!

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rowLibrary.setOnClickListener {
            com.play.tube.ui.library.LibraryActivity.start(requireContext())
        }
        // Settings now live on their own full pages (general / video / music)
        // instead of cramped bottom sheets.
        binding.rowSettings.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_GENERAL)
        }
        binding.rowVideoSettings.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_VIDEO)
        }
        binding.rowMusicSettings.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_MUSIC)
        }
        binding.rowTelegram.setOnClickListener {
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        Uri.parse(TELEGRAM_URL)
                    )
                )
            }
        }
        binding.rowFeedback.setOnClickListener {
            runCatching {
                startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL))
                )
            }
        }
        binding.rowDeveloper.setOnClickListener {
            com.play.tube.ui.menu.DeveloperActivity.start(requireContext())
        }
        // Import / export now live together in their own backup submenu
        // (clear data moved into the general Settings page).
        binding.rowBackup.setOnClickListener { showBackupSheet() }
        binding.rowCheckUpdate.setOnClickListener { checkForUpdate(manual = true) }

        binding.version.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
    }

    // ----- generic sheet plumbing -----

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun sheetRoot(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(28))
    }

    private fun sheetTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTextColor(Themes.onSurfaceColor(requireContext()))
        setTypeface(null, Typeface.BOLD)
    }

    private fun sheetMenuRow(mainLabel: String, valueLabel: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Themes.elevatedColor(requireContext()))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }

            addView(
                TextView(requireContext()).apply {
                    text = mainLabel
                    textSize = 15f
                    setTextColor(Themes.onSurfaceColor(requireContext()))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            addView(
                TextView(requireContext()).apply {
                    text = valueLabel
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Themes.onSurfaceVariantColor(requireContext()))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = dp(6) }
                }
            )
        }

    private fun showSheet(sheet: BottomSheetDialog, root: LinearLayout, peekDp: Int = 420) {
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.peekHeight = dp(peekDp)
        sheet.show()
    }

    // ----- Backup / restore submenu -----

    /** Dedicated import/export submenu: one menu entry, one sheet. */
    private fun showBackupSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val root = sheetRoot()
        root.addView(sheetTitle(getString(R.string.menu_backup_restore)))

        root.addView(
            sheetMenuRow(getString(R.string.menu_import), "") {
                runCatching {
                    importLauncher.launch(arrayOf("application/json"))
                }
            }
        )
        root.addView(
            sheetMenuRow(getString(R.string.menu_export), "") {
                runCatching {
                    exportLauncher.launch("playtube-backup.json")
                }
            }
        )
        showSheet(sheet, root, peekDp = 240)
    }

    // ----- Import / export -----

    private fun doImport(uri: Uri) {
        val ctx = context ?: return
        val act = activity
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = BackupManager.importFrom(ctx, uri)
            // The stored theme may have changed: re-apply the night mode and
            // refresh this screen right away when the visual prefs moved.
            Themes.applyDefaultNightMode()
            act?.let { Themes.recreateIfNeeded(it) }
            Toast.makeText(
                ctx,
                if (ok) R.string.import_done else R.string.import_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun doExport(uri: Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = BackupManager.exportTo(ctx, uri)
            Toast.makeText(
                ctx,
                if (ok) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ----- Update check -----

    private fun checkForUpdate(manual: Boolean) {
        val ctx = context ?: return
        if (!UpdateChecker.isConfigured) {
            if (manual) Toast.makeText(ctx, R.string.update_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        if (manual) {
            Toast.makeText(ctx, R.string.checking_update, Toast.LENGTH_SHORT).show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val info = UpdateChecker.refresh(ctx)
            if (info != null) {
                com.play.tube.ui.UpdateActivity.start(ctx)
            } else if (manual) {
                Toast.makeText(ctx, R.string.up_to_date, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Community link shown as its own menu card. */
        const val DEVELOPER_URL = "https://t.me/YellowlFlash"
        const val TELEGRAM_URL = "https://t.me/PlayTubeOfficial"
        const val FEEDBACK_URL = "https://t.me/+-ctzW77Fz5YwMWFl"
    }
}
