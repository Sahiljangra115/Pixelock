package dev.pixelock.app.ui.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.pixelock.app.R
import dev.pixelock.app.core.ServiceHealth
import dev.pixelock.app.databinding.FragmentListBinding
import dev.pixelock.app.databinding.RowItemBinding
import dev.pixelock.app.ui.activity.MainActivity
import dev.pixelock.app.ui.updateSettings

class SetupFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val checks = ArrayList<Pair<RowItemBinding, () -> Boolean>>()

    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.title.setText(R.string.setup_title)
        binding.back.setOnClickListener { finish() }
        val rows = binding.rows
        rows.addRow(getString(R.string.setup_intro), null)

        check(R.string.setup_a11y, R.string.setup_a11y_d, { ServiceHealth.isBlockerRunning(context) }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        rows.addRow(getString(R.string.setup_a11y_grey), null) {
            startActivity(ServiceHealth.appInfoIntent(context))
        }
        check(R.string.setup_overlay, R.string.setup_overlay_d, { Settings.canDrawOverlays(context) }) {
            startActivity(ServiceHealth.overlayIntent(context))
        }
        check(R.string.setup_notif, R.string.setup_notif_d, {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        check(R.string.setup_admin, R.string.setup_admin_d, { ServiceHealth.isAdminActive(context) }) {
            startActivity(ServiceHealth.adminIntent(context))
        }
        check(R.string.setup_battery, R.string.setup_battery_d, { ServiceHealth.isBatteryFree(context) }) {
            startActivity(ServiceHealth.batteryIntent(context))
        }
        ServiceHealth.autostartIntent(context)?.let { intent ->
            rows.addRow(getString(R.string.setup_auto), getString(R.string.setup_auto_d)) {
                runCatching { startActivity(intent) }
            }
        }
        rows.addRow(getString(R.string.setup_continue), null) { finish() }
    }

    private fun check(title: Int, desc: Int, done: () -> Boolean, open: () -> Unit) {
        val row = binding.rows.addRow(getString(title), getString(desc)) {
            if (!done()) runCatching(open)
        }
        row.status.visibility = View.VISIBLE
        checks += row to done
    }

    private fun finish() {
        updateSettings { it.copy(onboardingDone = true) }
        (activity as? MainActivity)?.closeOverlay()
    }

    private fun refresh() {
        if (_binding == null) return
        checks.forEach { (row, done) ->
            row.status.setText(if (done()) R.string.setup_done else R.string.setup_todo)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        checks.clear()
        _binding = null
    }
}
