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
import dev.pixelock.app.databinding.ViewThemeBlocksBinding
import dev.pixelock.app.domain.EndPassword
import dev.pixelock.app.ui.activity.AppPickerActivity
import dev.pixelock.app.ui.activity.MainActivity
import dev.pixelock.app.ui.askNewPassword
import dev.pixelock.app.ui.askSecret
import dev.pixelock.app.ui.onSettings
import dev.pixelock.app.ui.setUsable
import dev.pixelock.app.ui.updateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var themes: ViewThemeBlocksBinding? = null
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
        rows.addGroup().addPageRow(getString(R.string.friends), getString(R.string.s_friends_d)) {
            (activity as? MainActivity)?.openOverlay(FriendsFragment())
        }

        val focus = rows.addGroup(getString(R.string.s_focus))
        strictRow = focus.addRow(getString(R.string.s_strict), getString(R.string.s_strict_d)) { toggleStrict() }
        password = focus.addRow(getString(R.string.s_password), getString(R.string.s_password_d)) {
            if (timerRunning) refuse() else askNewPassword { render() }
        }
        apps = focus.addPageRow(getString(R.string.s_apps), null) {
            if (timerRunning) refuse() else picker.launch(
                AppPickerActivity.intent(requireContext(), settings.focusBlockList)
            )
        }
        shade = focus.addRow(getString(R.string.s_shade), getString(R.string.s_shade_d)) {
            ifEditable { s -> s.copy(shadeLockOn = !s.shadeLockOn) }
        }

        val blocks = ViewThemeBlocksBinding.inflate(layoutInflater, rows.addGroup(getString(R.string.s_theme)), true)
        blocks.themeSystem.setOnClickListener { setTheme(THEME_SYSTEM) }
        blocks.themeLight.setOnClickListener { setTheme(THEME_LIGHT) }
        blocks.themeDark.setOnClickListener { setTheme(THEME_DARK) }
        themes = blocks

        val phone = rows.addGroup(getString(R.string.s_phone))
        phone.addPageRow(getString(R.string.s_setup), getString(R.string.s_setup_d)) {
            (activity as? MainActivity)?.openOverlay(SetupFragment())
        }
        phone.addRow(getString(R.string.s_version, BuildConfig.VERSION_NAME), null) { onVersionTap() }

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

    private fun setTheme(mode: Int) {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME, mode).apply()
        applySavedTheme(requireContext())
        render()
    }

    private fun onVersionTap() {
        val now = SystemClock.uptimeMillis()
        tapCount = if (now - lastTap < TAP_WINDOW_MS) tapCount + 1 else 1
        lastTap = now
        if (tapCount >= VERSION_TAPS) {
            tapCount = 0
            openDeveloper()
        }
    }

    /**
     * Friends run this same APK, so the passcode is what keeps them off the kill switch.
     * ponytail: the salted hash ships in the APK, so someone who decompiles it can guess offline.
     * Fine against friends with a long passcode. A server check is the upgrade if that changes.
     */
    private fun openDeveloper() {
        val open = { (activity as? MainActivity)?.openOverlay(DeveloperFragment()) }
        if (BuildConfig.DEBUG) {
            open()
            return
        }
        askSecret(R.string.dev_passcode_t, null) { typed ->
            viewLifecycleOwner.lifecycleScope.launch {
                val right = withContext(Dispatchers.Default) {
                    EndPassword.matches(typed, BuildConfig.ADMIN_PASSCODE_SALT, BuildConfig.ADMIN_PASSCODE_HASH)
                }
                if (right) open() else Toast.makeText(requireContext(), R.string.dev_passcode_wrong, Toast.LENGTH_SHORT).show()
            }
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
        themes?.apply {
            themeSystem.isSelected = themeMode == THEME_SYSTEM
            themeLight.isSelected = themeMode == THEME_LIGHT
            themeDark.isSelected = themeMode == THEME_DARK
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
        themes = null
        paused = null
        password = null
        _binding = null
    }
}
