package dev.pixelock.app.ui.fragments

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.pixelock.app.BuildConfig
import dev.pixelock.app.R
import dev.pixelock.app.core.CrashGuard
import dev.pixelock.app.core.ServiceHealth
import dev.pixelock.app.core.StrictModeController
import dev.pixelock.app.data.DataStoreManager
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.data.models.THEME_DARK
import dev.pixelock.app.data.models.THEME_LIGHT
import dev.pixelock.app.data.models.THEME_SYSTEM
import dev.pixelock.app.databinding.FragmentListBinding
import dev.pixelock.app.databinding.RowItemBinding
import dev.pixelock.app.ui.activity.AppPickerActivity
import dev.pixelock.app.ui.activity.MainActivity
import dev.pixelock.app.ui.askNewPassword
import dev.pixelock.app.ui.onSettings
import dev.pixelock.app.ui.setUsable
import dev.pixelock.app.ui.updateSettings
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    companion object {
        private const val PREFS = "ui"
        private const val KEY_THEME = "theme"
        private const val VERSION_TAPS = 5
        private const val TAP_WINDOW_MS = 3_000L

        fun applySavedTheme(context: Context) {
            val mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_THEME, THEME_SYSTEM)
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private lateinit var strict: StrictModeController
    private var settings = Settings()
    private var timerRunning = false
    private var tapCount = 0
    private var lastTap = 0L

    private var shade: RowItemBinding? = null
    private var strictRow: RowItemBinding? = null
    private var apps: RowItemBinding? = null
    private var theme: RowItemBinding? = null
    private var paused: RowItemBinding? = null
    private var password: RowItemBinding? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val picked = result.data?.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED)
            ?: return@registerForActivityResult
        updateSettings { s -> if (strict.isTimerRunning(s)) s else s.copy(focusBlockList = picked) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        strict = StrictModeController(requireContext())
        binding.title.setText(R.string.settings)
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }

        val rows = binding.rows
        paused = rows.addRow(getString(R.string.s_paused_t), getString(R.string.s_paused_d)) { resume() }
        shade = rows.addRow(getString(R.string.s_shade), getString(R.string.s_shade_d)) {
            ifEditable { s -> s.copy(shadeLockOn = !s.shadeLockOn) }
        }
        strictRow = rows.addRow(getString(R.string.s_strict), getString(R.string.s_strict_d)) { toggleStrict() }
        password = rows.addRow(getString(R.string.s_password), getString(R.string.s_password_d)) {
            if (timerRunning) refuse() else askNewPassword { render() }
        }
        apps = rows.addRow(getString(R.string.s_apps), null) {
            if (timerRunning) refuse() else picker.launch(
                AppPickerActivity.intent(requireContext(), settings.focusBlockList)
            )
        }
        theme = rows.addRow(getString(R.string.s_theme), null) { cycleTheme() }
        rows.addRow(getString(R.string.s_setup), getString(R.string.s_setup_d)) {
            (activity as? MainActivity)?.openOverlay(SetupFragment())
        }
        rows.addRow(getString(R.string.s_version, BuildConfig.VERSION_NAME), null) { onVersionTap() }

        listOf(shade, strictRow).forEach { it?.toggle?.visibility = View.VISIBLE }
        onSettings { s ->
            settings = s
            timerRunning = strict.isTimerRunning(s)
            render()
        }
    }

    private fun ifEditable(transform: (Settings) -> Settings) {
        if (timerRunning) refuse() else updateSettings { s -> if (strict.isTimerRunning(s)) s else transform(s) }
    }

    private fun refuse() {
        Toast.makeText(requireContext(), R.string.edit_refused, Toast.LENGTH_SHORT).show()
    }

    private fun toggleStrict() {
        val turnOn = !strict.isEnabled(settings)
        val context = requireContext().applicationContext
        if (!turnOn) {
            if (timerRunning) refuse() else viewLifecycleOwner.lifecycleScope.launch { strict.setEnabled(false) }
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.s_strict_confirm_t)
            .setMessage(R.string.s_strict_confirm)
            .setPositiveButton(R.string.s_strict_on) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { strict.setEnabled(true) }
                if (!ServiceHealth.isAdminActive(context)) {
                    startActivity(ServiceHealth.adminIntent(context))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resume() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            CrashGuard.clear(context)
            DataStoreManager(context).update { it.copy(failSafeRev = it.failSafeRev + 1) }
            render()
        }
    }

    private fun cycleTheme() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = (prefs.getInt(KEY_THEME, THEME_SYSTEM) + 1) % 3
        prefs.edit().putInt(KEY_THEME, next).apply()
        applySavedTheme(requireContext())
        render()
    }

    private fun onVersionTap() {
        val now = SystemClock.uptimeMillis()
        tapCount = if (now - lastTap < TAP_WINDOW_MS) tapCount + 1 else 1
        lastTap = now
        if (tapCount >= VERSION_TAPS) {
            tapCount = 0
            (activity as? MainActivity)?.openOverlay(DeveloperFragment())
        }
    }

    private fun render() {
        if (_binding == null) return
        val context = requireContext()
        paused?.root?.visibility = if (CrashGuard.isFailSafe(context)) View.VISIBLE else View.GONE
        shade?.toggle?.isChecked = settings.shadeLockOn
        strictRow?.toggle?.isChecked = strict.isEnabled(settings)
        apps?.desc?.apply {
            text = resources.getQuantityString(R.plurals.app_count, settings.focusBlockList.size, settings.focusBlockList.size)
            visibility = View.VISIBLE
        }
        val themeMode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, THEME_SYSTEM)
        theme?.desc?.apply {
            setText(
                when (themeMode) {
                    THEME_LIGHT -> R.string.theme_light
                    THEME_DARK -> R.string.theme_dark
                    else -> R.string.theme_system
                }
            )
            visibility = View.VISIBLE
        }
        password?.status?.apply {
            setText(if (settings.endPasswordHash.isEmpty()) R.string.s_password_unset else R.string.s_password_set)
            visibility = View.VISIBLE
        }
        // Any of these could open a running timer's apps without its password.
        listOf(shade, apps).forEach { it?.root?.setUsable(!timerRunning) }
        // Strict can always be turned on. Only turning it off waits for the timer.
        strictRow?.root?.setUsable(!timerRunning || !strict.isEnabled(settings))
        password?.root?.setUsable(!timerRunning)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shade = null
        strictRow = null
        apps = null
        theme = null
        paused = null
        password = null
        _binding = null
    }
}
