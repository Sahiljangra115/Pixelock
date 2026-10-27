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
import dev.pixelock.app.ui.confirmAfterWait
import dev.pixelock.app.ui.onSettings
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
    private var killSwitch: RowItemBinding? = null
    private var remove: RowItemBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        strict = StrictModeController(requireContext())
        binding.title.setText(R.string.dev_title)
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }

        killSwitch = binding.rows.addRow(getString(R.string.dev_switch), getString(R.string.dev_switch_d)) {
            toggle()
        }.also { it.toggle.visibility = View.VISIBLE }
        remove = binding.rows.addRow(getString(R.string.dev_remove), getString(R.string.dev_remove_d)) {
            removeGuard()
        }

        val log = DecisionLog.read(requireContext())
        binding.rows.addRow(
            getString(R.string.dev_log),
            log.ifBlank { getString(R.string.dev_log_empty) }
        )

        onSettings { s ->
            settings = s
            killSwitch?.toggle?.isChecked = strict.isEnforcementDisabled(s)
            showRemove()
        }
    }

    private fun showRemove() {
        remove?.root?.visibility = if (ServiceHealth.isAdminActive(requireContext())) View.VISIBLE else View.GONE
    }

    private fun removeGuard() {
        if (strict.isEnforcing(settings)) {
            Toast.makeText(requireContext(), R.string.dev_remove_refused, Toast.LENGTH_LONG).show()
            return
        }
        ServiceHealth.removeAdmin(requireContext())
        showRemove()
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
                showRemove()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showRemove()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        killSwitch = null
        remove = null
        _binding = null
    }
}
