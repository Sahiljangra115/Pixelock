package dev.pixelock.app.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.pixelock.app.R
import dev.pixelock.app.core.PaymentFlow
import dev.pixelock.app.data.remote.OrderPurpose
import dev.pixelock.app.databinding.ActivityBlockBinding
import dev.pixelock.app.domain.BlockReason
import dev.pixelock.app.domain.Decision

/**
 * The block screen. Always has a way out: leaving goes home, so it can never hold the phone.
 */
class BlockActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_UNTIL = "until"
        private const val EXTRA_BACK_ONLY = "back_only"

        fun intent(context: Context, pkg: String, decision: Decision.Block, backOnly: Boolean): Intent =
            Intent(context, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_REASON, decision.reason.name)
                .putExtra(EXTRA_UNTIL, decision.untilMs)
                .putExtra(EXTRA_BACK_ONLY, backOnly)
    }

    private lateinit var binding: ActivityBlockBinding
    private val ticker = Handler(Looper.getMainLooper())
    private var untilMs = 0L
    private var backOnly = false
    private var pkg = ""

    private val tick = object : Runnable {
        override fun run() {
            val left = untilMs - System.currentTimeMillis()
            if (left <= 0) {
                finish()
                return
            }
            val seconds = left / 1000
            binding.clock.text = if (seconds >= 3600) "%02d:%02d".format(seconds / 3600, seconds / 60 % 60)
            else "%02d:%02d".format(seconds / 60, seconds % 60)
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left + root.paddingStart, bars.top, bars.right + root.paddingEnd, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        binding.leave.setOnClickListener { leave() }
        binding.pay.setOnClickListener {
            startActivity(PaymentActivity.intent(this, OrderPurpose.UNLOCK_APP, pkg))
            finish()
        }
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val reason = runCatching { BlockReason.valueOf(intent.getStringExtra(EXTRA_REASON).orEmpty()) }
            .getOrDefault(BlockReason.SESSION)
        untilMs = intent.getLongExtra(EXTRA_UNTIL, 0L)
        backOnly = intent.getBooleanExtra(EXTRA_BACK_ONLY, false)

        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        binding.title.text = when (reason) {
            BlockReason.SHORT_VIDEO -> getString(R.string.blocked_short_title)
            // Often the store's page, so the store's name would be the wrong one here.
            BlockReason.BANNED -> getString(R.string.blocked_banned_title)
            else -> getString(R.string.blocked_title, label)
        }
        binding.reason.setText(
            when (reason) {
                BlockReason.SHORT_VIDEO -> R.string.reason_short
                BlockReason.SESSION -> R.string.reason_session
                BlockReason.ROUTINE -> R.string.reason_routine
                BlockReason.BAG -> R.string.reason_bag
                BlockReason.BANNED -> R.string.reason_banned
            }
        )
        binding.leave.setText(if (backOnly) R.string.go_back else R.string.go_home)
        // Short video stays closed for everyone: a pass opens an app, not a feed.
        val payable = reason != BlockReason.SHORT_VIDEO && reason != BlockReason.BANNED
        binding.pay.visibility = if (payable) View.VISIBLE else View.GONE
        binding.pay.text = getString(R.string.pay_open, PaymentFlow.UNLOCK_MINUTES)
        binding.clock.visibility = if (untilMs > 0) View.VISIBLE else View.INVISIBLE
        binding.clock.setActive(true)
    }

    private fun leave() {
        if (!backOnly) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (untilMs > 0) tick.run()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }
}
