package dev.pixelock.app.ui.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.pixelock.app.R
import dev.pixelock.app.core.ServiceHealth
import dev.pixelock.app.databinding.FragmentListBinding
import dev.pixelock.app.databinding.RowItemBinding
import dev.pixelock.app.hardcoded.OemAutostartIntents
import dev.pixelock.app.ui.activity.MainActivity
import dev.pixelock.app.ui.updateSettings

class SetupFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val checks = ArrayList<Pair<RowItemBinding, () -> Boolean>>()
    private var greyHint: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.title.setText(R.string.setup_title)
        binding.back.setOnClickListener { finish() }
        val rows = binding.rows
        rows.addNote(getString(R.string.setup_intro))

        rows.addHeading(getString(R.string.setup_needed))
        permission(R.string.setup_a11y, R.string.setup_a11y_d, { ServiceHealth.isBlockerRunning(context) }) {
            openFirst(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        greyHint = rows.addNote(getString(R.string.setup_a11y_grey)) {
            openFirst(ServiceHealth.appInfoIntent(context))
        }

        rows.addHeading(getString(R.string.setup_helps))
        permission(R.string.setup_notif, R.string.setup_notif_d, {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }) {
            // The system page, because the permission prompt goes silent after two refusals.
            openFirst(ServiceHealth.notificationIntent(context))
        }
        permission(R.string.setup_battery, R.string.setup_battery_d, { ServiceHealth.isBatteryFree(context) }) {
            openFirst(ServiceHealth.batteryIntent(context))
        }
        permission(R.string.setup_admin, R.string.setup_admin_d, { ServiceHealth.isAdminActive(context) }) {
            // Android has no page for one app's admin, and removing it lives on the hidden screen.
            if (ServiceHealth.isAdminActive(context)) {
                Toast.makeText(context, R.string.setup_admin_on, Toast.LENGTH_SHORT).show()
            } else {
                openFirst(ServiceHealth.adminIntent(context))
            }
        }
        ServiceHealth.autostartIntent(context)?.let { intent ->
            rows.addRow(getString(R.string.setup_auto), getString(R.string.setup_auto_d)) {
                openFirst(intent)
            }.also {
                it.status.setText(R.string.setup_open)
                it.status.visibility = View.VISIBLE
            }
        }
        // No page exists for this one, and on these phones a swipe in recents can end the blocker.
        if (Build.MANUFACTURER.lowercase() in OemAutostartIntents.swipeKillers) {
            rows.addRow(getString(R.string.setup_lock), getString(R.string.setup_lock_d))
        }
        rows.addButton(getString(R.string.setup_continue)) { finish() }
    }

    /** The switch only shows what the phone says. Tapping always opens the real setting. */
    private fun permission(title: Int, desc: Int, granted: () -> Boolean, open: () -> Unit) {
        val row = binding.rows.addRow(getString(title), getString(desc), open)
        row.toggle.visibility = View.VISIBLE
        checks += row to granted
    }

    /** Skins drop or guard some of these pages, so a tap always lands on something real. */
    private fun openFirst(vararg intents: Intent) {
        (intents.asList() + ServiceHealth.appInfoIntent(requireContext()))
            .firstOrNull { runCatching { startActivity(it) }.isSuccess }
    }

    private fun finish() {
        updateSettings { it.copy(onboardingDone = true) }
        (activity as? MainActivity)?.closeOverlay()
    }

    private fun refresh() {
        if (_binding == null) return
        checks.forEach { (row, granted) -> row.toggle.isChecked = granted() }
        greyHint?.visibility = if (ServiceHealth.isBlockerRunning(requireContext())) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        checks.clear()
        greyHint = null
        _binding = null
    }
}
