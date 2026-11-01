package dev.pixelock.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.pixelock.app.R
import dev.pixelock.app.core.InstalledApps
import dev.pixelock.app.core.StrictModeController
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.databinding.FragmentListBinding
import dev.pixelock.app.domain.BannedApp
import dev.pixelock.app.hardcoded.InstallBlockCatalog
import dev.pixelock.app.ui.askText
import dev.pixelock.app.ui.onSettings
import dev.pixelock.app.ui.updateSettings

/** Picks the apps kept off this phone. Reached only from the hidden screen. */
class InstallBlockFragment : Fragment() {

    private companion object {
        const val NAME_MAX = 40
    }

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private lateinit var strict: StrictModeController
    private var settings = Settings()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        strict = StrictModeController(requireContext())
        binding.title.setText(R.string.dev_banned)
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }
        onSettings { s ->
            settings = s
            render()
        }
    }

    private fun render() {
        val rows = _binding?.rows ?: return
        rows.removeAllViews()
        rows.addNote(getString(R.string.banned_note))
        val panel = rows.addGroup()
        InstallBlockCatalog.apps.forEach { panel.addApp(it) }
        // Typed names sit below, and tapping one drops it, since no catalog row can bring it back.
        settings.bannedApps.filter { app -> InstallBlockCatalog.apps.none { it.name == app.name } }
            .forEach { panel.addApp(it) }
        rows.addButton(getString(R.string.banned_add)) { addByName() }
    }

    private fun LinearLayout.addApp(app: BannedApp) {
        val row = addRow(app.name, null) { toggle(app) }
        row.toggle.visibility = View.VISIBLE
        row.toggle.isChecked = settings.bannedApps.any { it.name == app.name }
    }

    /** A timer locks this list both ways, or removing an app would open it mid timer. */
    private fun edit(transform: (List<BannedApp>) -> List<BannedApp>) {
        if (strict.isTimerRunning(settings)) {
            Toast.makeText(requireContext(), R.string.edit_refused, Toast.LENGTH_SHORT).show()
            return
        }
        updateSettings { s -> if (strict.isTimerRunning(s)) s else s.copy(bannedApps = transform(s.bannedApps)) }
    }

    private fun toggle(app: BannedApp) = edit { list ->
        if (list.any { it.name == app.name }) list.filter { it.name != app.name } else list + app
    }

    private fun addByName() {
        askText(R.string.banned_add, R.string.banned_add_hint, "", NAME_MAX) { name ->
            // ponytail: exact label only. A name with no package still guards store pages.
            val packages = InstalledApps.cached.filter { it.label.equals(name, ignoreCase = true) }.map { it.pkg }
            edit { list ->
                if (list.any { it.name.equals(name, ignoreCase = true) }) list else list + BannedApp(name, packages)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
