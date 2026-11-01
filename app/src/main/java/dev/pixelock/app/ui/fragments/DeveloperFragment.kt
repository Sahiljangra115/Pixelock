package dev.pixelock.app.ui.fragments

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.pixelock.app.R
import dev.pixelock.app.core.DecisionLog
import dev.pixelock.app.core.ServiceHealth
import dev.pixelock.app.core.SessionManager
import dev.pixelock.app.core.StrictModeController
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.databinding.FragmentListBinding
import dev.pixelock.app.databinding.RowItemBinding
import dev.pixelock.app.ui.activity.MainActivity
import dev.pixelock.app.ui.confirmAfterWait
import dev.pixelock.app.ui.onSettings
import dev.pixelock.app.ui.setUsable
import dev.pixelock.app.ui.updateSettings
import kotlinx.coroutines.launch

/** Reached only by tapping the version row. Nothing in the app points here. */
class DeveloperFragment : Fragment() {

    private companion object {
        const val KILL_WAIT_SECONDS = 30
    }

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private lateinit var strict: StrictModeController
    private var settings = Settings()
    private var shortVideo: RowItemBinding? = null
    private var killSwitch: RowItemBinding? = null
    private var lock: RowItemBinding? = null
    private var banned: RowItemBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        strict = StrictModeController(requireContext())
        binding.title.setText(R.string.dev_title)
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }

        val always = binding.rows.addGroup()
        shortVideo = always.addRow(getString(R.string.s_short_video), getString(R.string.s_short_video_d)) {
            toggleShortVideo()
        }.also { it.toggle.visibility = View.VISIBLE }
        lock = always.addRow(getString(R.string.dev_lock), getString(R.string.dev_lock_d)) {
            toggleLock()
        }.also { it.toggle.visibility = View.VISIBLE }
        banned = always.addPageRow(getString(R.string.dev_banned), null) {
            (activity as? MainActivity)?.openOverlay(InstallBlockFragment())
        }
        killSwitch = binding.rows.addGroup().addRow(getString(R.string.dev_switch), getString(R.string.dev_switch_d)) {
            toggle()
        }.also { it.toggle.visibility = View.VISIBLE }

        val log = DecisionLog.read(requireContext())
        binding.rows.addGroup().addRow(
            getString(R.string.dev_log),
            log.ifBlank { getString(R.string.dev_log_empty) }
        )

        onSettings { s ->
            settings = s
            shortVideo?.toggle?.isChecked = s.shortVideoBlockingOn
            shortVideo?.root?.setUsable(!strict.isTimerRunning(s))
            killSwitch?.toggle?.isChecked = strict.isEnforcementDisabled(s)
            banned?.desc?.apply {
                text = resources.getQuantityString(R.plurals.app_count, s.bannedApps.size, s.bannedApps.size)
                visibility = View.VISIBLE
            }
            showLock()
        }
    }

    /** Off could open a running timer's apps without its password, so a timer locks it. */
    private fun toggleShortVideo() {
        if (strict.isTimerRunning(settings)) {
            Toast.makeText(requireContext(), R.string.edit_refused, Toast.LENGTH_SHORT).show()
            return
        }
        updateSettings { s -> if (strict.isTimerRunning(s)) s else s.copy(shortVideoBlockingOn = !s.shortVideoBlockingOn) }
    }

    /** On means Android refuses the uninstall too, so the switch shows both halves. */
    private fun isLocked() = settings.uninstallLock && ServiceHealth.isAdminActive(requireContext())

    private fun showLock() {
        lock?.toggle?.isChecked = isLocked()
    }

    private fun toggleLock() {
        val context = requireContext()
        if (!isLocked()) {
            updateSettings { it.copy(uninstallLock = true) }
            if (!ServiceHealth.isAdminActive(context)) startActivity(ServiceHealth.adminIntent(context))
            return
        }
        if (strict.isEnforcing(settings)) {
            Toast.makeText(context, R.string.dev_lock_refused, Toast.LENGTH_LONG).show()
            return
        }
        updateSettings { it.copy(uninstallLock = false) }
        ServiceHealth.removeAdmin(context)
    }

    private fun toggle() {
        if (strict.isEnforcementDisabled(settings)) {
            viewLifecycleOwner.lifecycleScope.launch { strict.setEnforcementDisabled(false) }
            return
        }
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dev_confirm_t)
            .setMessage(R.string.dev_confirm)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (input.text.toString().trim() == "OFF") confirmKill()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmKill() {
        val context = requireContext().applicationContext
        confirmAfterWait(R.string.dev_wait_t, getString(R.string.dev_wait), KILL_WAIT_SECONDS) {
            viewLifecycleOwner.lifecycleScope.launch {
                SessionManager.killEverything(context)
                showLock()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showLock()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shortVideo = null
        killSwitch = null
        lock = null
        banned = null
        _binding = null
    }
}
