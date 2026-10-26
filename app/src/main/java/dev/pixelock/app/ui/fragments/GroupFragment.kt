package dev.pixelock.app.ui.fragments

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.pixelock.app.BuildConfig
import dev.pixelock.app.R
import dev.pixelock.app.core.GroupSync
import dev.pixelock.app.data.remote.BackendException
import dev.pixelock.app.data.remote.Backends
import dev.pixelock.app.databinding.DialogInviteBinding
import dev.pixelock.app.databinding.FragmentListBinding
import dev.pixelock.app.databinding.ViewBoardTabsBinding
import dev.pixelock.app.domain.BoardPeriod
import dev.pixelock.app.domain.BoardRow
import dev.pixelock.app.domain.inviteLink
import dev.pixelock.app.domain.isEmail
import dev.pixelock.app.domain.parseEmails
import dev.pixelock.app.domain.range
import dev.pixelock.app.ui.activity.ContactPickerActivity
import dev.pixelock.app.ui.askText
import dev.pixelock.app.ui.durationLabel
import dev.pixelock.app.ui.messageRes
import dev.pixelock.app.ui.online
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** One group's leaderboard. Shows only aliases and totals. */
class GroupFragment : Fragment() {

    companion object {
        private const val ARG_CODE = "code"
        private const val QR_PX = 720
        private const val EMAILS_MAX = 1000

        fun create(code: String) = GroupFragment().apply { arguments = bundleOf(ARG_CODE to code) }
    }

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private var tabs: ViewBoardTabsBinding? = null
    private var board: LinearLayout? = null
    private var period = BoardPeriod.TODAY
    private var loadJob: Job? = null
    private val code get() = requireArguments().getString(ARG_CODE).orEmpty()

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val picked = result.data?.getStringArrayListExtra(ContactPickerActivity.EXTRA_SELECTED).orEmpty()
        if (picked.isNotEmpty()) sendInvites(picked)
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) contactPicker.launch(ContactPickerActivity.intent(requireContext()))
        else Toast.makeText(requireContext(), R.string.contacts_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.title.text = getString(R.string.friends_group_row, code)
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }

        val rows = binding.rows
        tabs = ViewBoardTabsBinding.inflate(layoutInflater, rows, true).also {
            it.today.setOnClickListener { setPeriod(BoardPeriod.TODAY) }
            it.week.setOnClickListener { setPeriod(BoardPeriod.WEEK) }
            it.month.setOnClickListener { setPeriod(BoardPeriod.MONTH) }
        }
        board = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }.also(rows::addView)
        rows.addRow(getString(R.string.group_contacts), getString(R.string.group_contacts_d)) { pickContacts() }
        rows.addRow(getString(R.string.group_email), getString(R.string.group_email_d)) { askEmails() }
        rows.addRow(getString(R.string.group_invite), getString(R.string.group_invite_d)) { showInvite() }
        rows.addRow(getString(R.string.group_leave), getString(R.string.group_leave_d)) { confirmLeave() }
        setPeriod(period)
    }

    private fun setPeriod(next: BoardPeriod) {
        period = next
        tabs?.apply {
            today.isSelected = next == BoardPeriod.TODAY
            week.isSelected = next == BoardPeriod.WEEK
            month.isSelected = next == BoardPeriod.MONTH
        }
        loadJob?.cancel()
        val owner = viewLifecycleOwner
        loadJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    load()
                    delay(TimeUnit.MINUTES.toMillis(30))
                }
            }
        }
    }

    private suspend fun load() {
        val context = requireContext().applicationContext
        val (from, to) = period.range(LocalDate.now())
        if (board?.childCount == 0) showMessage(getString(R.string.board_loading))
        try {
            val rows = withContext(Dispatchers.IO) {
                runCatching { GroupSync.push(context) } // An old board still beats no board.
                Backends.get(context).board(code, from.toString(), to.toString())
            }
            show(rows)
        } catch (e: BackendException) {
            showMessage(getString(e.messageRes()))
        }
    }

    private fun show(rows: List<BoardRow>) {
        val list = board ?: return
        list.removeAllViews()
        rows.forEachIndexed { i, row ->
            val name = if (row.isMe) getString(R.string.board_you, row.alias) else row.alias
            val item = list.addRow(
                getString(R.string.board_rank, i + 1, name),
                getString(R.string.board_distracted, requireContext().durationLabel(row.distractedMin.toInt()))
            )
            item.status.text = requireContext().durationLabel(row.focusedMin.toInt())
            item.status.visibility = View.VISIBLE
        }
    }

    private fun showMessage(text: String) {
        board?.removeAllViews()
        board?.addRow(text, null)
    }

    private fun showInvite() {
        val link = inviteLink(BuildConfig.SUPABASE_URL, code)
        val bitmap = qrBitmap(link)
        val view = DialogInviteBinding.inflate(layoutInflater)
        view.qr.setImageBitmap(bitmap)
        view.code.text = code
        MaterialAlertDialogBuilder(requireContext())
            .setView(view.root)
            .setPositiveButton(R.string.invite_share) { _, _ -> share(link) }
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener {
                view.qr.setImageDrawable(null)
                bitmap.recycle()
            }
            .show()
    }

    private fun share(link: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.invite_text, link, code))
        startActivity(Intent.createChooser(send, getString(R.string.invite_share)))
    }

    /** Asked for only here, so nobody who skips this feature ever sees the prompt. */
    private fun pickContacts() {
        val context = requireContext()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contactPicker.launch(ContactPickerActivity.intent(context))
        } else {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun askEmails() {
        askText(R.string.group_email, R.string.group_email_hint, "", EMAILS_MAX) { text ->
            val emails = parseEmails(text)
            if (emails.isEmpty()) Toast.makeText(requireContext(), R.string.err_bad_email, Toast.LENGTH_LONG).show()
            else sendInvites(emails)
        }
    }

    /**
     * Hands the invite to the phone's own mail and message apps, so it comes from the user and
     * the server sends nothing. Friends are blind copied, so they do not see each other's emails.
     */
    private fun sendInvites(addresses: List<String>) {
        val link = inviteLink(BuildConfig.SUPABASE_URL, code)
        val text = getString(R.string.invite_text, link, code)
        val (emails, phones) = addresses.partition(::isEmail)
        val intents = buildList {
            if (phones.isNotEmpty()) {
                add(
                    Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phones.joinToString(";"), null))
                        .putExtra("sms_body", text)
                )
            }
            if (emails.isNotEmpty()) {
                add(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                        .putExtra(Intent.EXTRA_BCC, emails.toTypedArray())
                        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.invite_subject))
                        .putExtra(Intent.EXTRA_TEXT, text)
                )
            }
        }
        try {
            requireContext().startActivities(intents.toTypedArray())
        } catch (_: ActivityNotFoundException) {
            share(link)
        }
    }

    private fun qrBitmap(text: String): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_PX, QR_PX)
        val pixels = IntArray(QR_PX * QR_PX) { i ->
            if (matrix[i % QR_PX, i / QR_PX]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, QR_PX, QR_PX, Bitmap.Config.RGB_565)
    }

    private fun confirmLeave() {
        val context = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.group_leave_t)
            .setPositiveButton(R.string.group_leave) { _, _ ->
                online {
                    GroupSync.leave(context, code)
                    parentFragmentManager.popBackStack()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabs = null
        board = null
        _binding = null
    }
}
