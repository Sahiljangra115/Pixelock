package dev.pixelock.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.pixelock.app.R
import dev.pixelock.app.core.GroupSync
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.data.remote.Backends
import dev.pixelock.app.databinding.FragmentListBinding
import dev.pixelock.app.domain.ALIAS_MAX
import dev.pixelock.app.domain.GROUP_NAME_MAX
import dev.pixelock.app.domain.LaptopToken
import dev.pixelock.app.domain.parseGroupCode
import dev.pixelock.app.ui.activity.MainActivity
import dev.pixelock.app.ui.askText
import dev.pixelock.app.ui.durationLabel
import dev.pixelock.app.ui.onSettings
import dev.pixelock.app.ui.online
import dev.pixelock.app.ui.updateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class FriendsFragment : Fragment() {

    companion object {
        private const val ARG_JOIN = "join"
        private const val CODE_MAX = 200

        fun create(joinCode: String) = FriendsFragment().apply { arguments = bundleOf(ARG_JOIN to joinCode) }
    }

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private var settings = Settings()
    private var joinCode: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.title.setText(R.string.friends)
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }
        if (savedInstanceState == null) joinCode = arguments?.getString(ARG_JOIN)

        onSettings { s ->
            settings = s
            render()
            joinCode?.let {
                joinCode = null
                confirmJoin(it)
            }
        }
    }

    private fun render() {
        val rows = _binding?.rows ?: return
        val context = requireContext()
        rows.removeAllViews()
        rows.addGroup().addRow(
            settings.alias.ifEmpty { getString(R.string.friends_name_empty) },
            getString(R.string.friends_name_d)
        ) { rename() }
        val groups = rows.addGroup()
        settings.groups.forEach { code ->
            groups.addPageRow(settings.groupNames[code] ?: getString(R.string.friends_group_row, code), null) {
                (activity as? MainActivity)?.openOverlay(GroupFragment.create(code))
            }
        }
        groups.addRow(getString(R.string.friends_create), null) { makeGroup() }
        groups.addRow(getString(R.string.friends_join), null) {
            askText(R.string.friends_join, R.string.friends_code_hint, "", CODE_MAX) { handleCode(it) }
        }
        groups.addRow(getString(R.string.friends_scan), getString(R.string.friends_scan_d)) { scan() }
        val more = rows.addGroup()
        more.addRow(
            getString(R.string.laptop),
            getString(R.string.laptop_link_d)
        ) { showLaptop() }
        if (settings.groups.isNotEmpty()) {
            more.addRow(getString(R.string.friends_delete), getString(R.string.friends_delete_d)) { confirmDelete() }
        }
    }

    /** Goes straight on to inviting, since a group of one is no use. */
    private fun makeGroup() {
        val context = requireContext().applicationContext
        withAlias { alias ->
            askText(R.string.group_name, R.string.group_name_hint, "", GROUP_NAME_MAX) { name ->
                online {
                    val code = GroupSync.create(context, alias, name)
                    (activity as? MainActivity)?.openOverlay(GroupFragment.create(code, invite = true))
                }
            }
        }
    }

    private fun withAlias(action: (String) -> Unit) {
        if (settings.alias.isNotEmpty()) action(settings.alias)
        else askText(R.string.friends_name, R.string.friends_name_hint, "", ALIAS_MAX, action)
    }

    private fun rename() {
        val context = requireContext().applicationContext
        askText(R.string.friends_name, R.string.friends_name_hint, settings.alias, ALIAS_MAX) { alias ->
            if (settings.groups.isEmpty()) updateSettings { it.copy(alias = alias) }
            else online { GroupSync.rename(context, alias) }
        }
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val context = requireContext()
        // The scanner comes from Play services, so it can be missing or still downloading.
        runCatching {
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { if (isAdded) handleCode(it.rawValue.orEmpty()) }
                .addOnFailureListener { toast(R.string.err_scan) }
        }.onFailure { toast(R.string.err_scan) }
    }

    /** Everything typed or scanned lands here. */
    private fun handleCode(text: String) {
        if (LaptopToken.isToken(text)) {
            addLaptop(text)
            return
        }
        val code = parseGroupCode(text)
        if (code == null) toast(R.string.err_bad_code) else confirmJoin(code)
    }

    private fun confirmJoin(code: String) {
        val context = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.friends_join_t)
            .setMessage(getString(R.string.friends_join_m, code))
            .setPositiveButton(R.string.join) { _, _ ->
                withAlias { alias -> online { GroupSync.join(context, code, alias) } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        val context = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.friends_delete_t)
            .setMessage(R.string.friends_delete_m)
            .setPositiveButton(R.string.delete) { _, _ -> online { GroupSync.deleteAll(context) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Makes a one time code. The laptop script spends it once and from then on syncs on its own. */
    private fun showLaptop() {
        val context = requireContext()
        online {
            val code = withContext(Dispatchers.IO) { Backends.get(context.applicationContext).createPairCode() }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.laptop_t)
                .setMessage(getString(R.string.laptop_link_steps, code))
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun addLaptop(text: String) {
        val day = LaptopToken.parse(text, settings.laptopKey, LocalDate.now())
        if (day == null) {
            toast(R.string.err_bad_laptop)
            return
        }
        val context = requireContext()
        val message = getString(R.string.laptop_added, context.durationLabel(day.minutes), day.date.toString())
        online {
            GroupSync.addLaptop(context.applicationContext, day)
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun toast(message: Int) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
