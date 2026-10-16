package dev.pixelock.app.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.pixelock.app.R
import dev.pixelock.app.core.GroupSync
import dev.pixelock.app.core.PaymentFlow
import dev.pixelock.app.core.SessionManager
import dev.pixelock.app.data.DataStoreManager
import dev.pixelock.app.data.db.AppDatabase
import dev.pixelock.app.data.remote.OrderPurpose
import dev.pixelock.app.databinding.ActivityMainBinding
import dev.pixelock.app.domain.parseGroupCode
import dev.pixelock.app.services.ServiceWatchdogJob
import dev.pixelock.app.ui.fragments.BagsFragment
import dev.pixelock.app.ui.fragments.FriendsFragment
import dev.pixelock.app.ui.fragments.RoutinesFragment
import dev.pixelock.app.ui.fragments.SettingsFragment
import dev.pixelock.app.ui.fragments.SetupFragment
import dev.pixelock.app.ui.fragments.StatsFragment
import dev.pixelock.app.ui.fragments.TimerFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        private const val KEY_TAB = "tab"
        private const val OVERLAY_TAG = "overlay"
    }

    private lateinit var binding: ActivityMainBinding
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsFragment.applySavedTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val buttons = listOf(binding.navTimer, binding.navBags, binding.navRoutines, binding.navStats)
        buttons.forEachIndexed { i, button -> button.setOnClickListener { showTab(i) } }
        binding.settingsButton.setOnClickListener { openOverlay(SettingsFragment()) }

        tab = savedInstanceState?.getInt(KEY_TAB) ?: 0
        showTab(tab)
        supportFragmentManager.addOnBackStackChangedListener { syncOverlay() }
        syncOverlay()

        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) binding.root.post { openOverlay(SettingsFragment()) }
        if (savedInstanceState == null) binding.root.post { openInvite(intent) }

        lifecycleScope.launch {
            val settings = DataStoreManager(this@MainActivity).settings.first()
            if (!settings.onboardingDone && savedInstanceState == null) openOverlay(SetupFragment())
            runCatching { ServiceWatchdogJob.schedule(this@MainActivity) }
            if (settings.groups.isNotEmpty()) runCatching { GroupSync.schedule(this@MainActivity) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { settleLatePayments() }
        }

        val dao = AppDatabase.getInstance(this).dayTotalDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionManager.reconcile(this@MainActivity)
                val today = LocalDate.now().toString()
                dao.observeRange(today, today).collect { rows ->
                    binding.blocksCount.text = (rows.firstOrNull()?.blocks ?: 0).toString()
                    binding.blocksPill.contentDescription =
                        getString(R.string.blocks_today, rows.firstOrNull()?.blocks ?: 0)
                }
            }
        }
    }

    /** A payment can land after its page was closed. This is where it gets picked up. */
    private suspend fun settleLatePayments() {
        PaymentFlow.settlePending(this).firstOrNull()?.let { order ->
            val done = if (order.purpose == OrderPurpose.UNLOCK_APP) {
                getString(R.string.pay_late_app, PaymentFlow.UNLOCK_MINUTES)
            } else {
                getString(R.string.pay_late_timer)
            }
            Toast.makeText(this, getString(R.string.pay_late, done), Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) openOverlay(SettingsFragment())
        openInvite(intent)
    }

    /** Only opens the question. Tapping a link can never join a group by itself. */
    private fun openInvite(intent: Intent) {
        val code = intent.dataString?.let(::parseGroupCode) ?: return
        openOverlay(FriendsFragment.create(code))
    }

    private fun newTab(index: Int): Fragment = when (index) {
        0 -> TimerFragment()
        1 -> BagsFragment()
        2 -> RoutinesFragment()
        else -> StatsFragment()
    }

    /** Tabs inflate on first open and stay alive after that, so switching stays instant. */
    private fun showTab(index: Int) {
        tab = index
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        for (i in 0 until 4) {
            val tag = "tab$i"
            val existing = manager.findFragmentByTag(tag)
            when {
                i == index && existing == null -> transaction.add(R.id.content, newTab(i), tag)
                i == index -> transaction.show(existing!!)
                existing != null -> transaction.hide(existing)
            }
        }
        transaction.commit()
        val buttons = listOf(binding.navTimer, binding.navBags, binding.navRoutines, binding.navStats)
        buttons.forEachIndexed { i, button -> button.isSelected = i == index }
        binding.logo.accentGlyph = index
    }

    fun openOverlay(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        // Overlays have no background of their own, so the one below must not show through.
        supportFragmentManager.findFragmentById(R.id.overlay)?.let { transaction.hide(it) }
        transaction.add(R.id.overlay, fragment, OVERLAY_TAG)
            .addToBackStack(null)
            .commit()
        binding.overlay.visibility = View.VISIBLE
    }

    fun closeOverlay() {
        supportFragmentManager.popBackStack()
    }

    private fun syncOverlay() {
        binding.overlay.visibility =
            if (supportFragmentManager.backStackEntryCount > 0) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }
}
