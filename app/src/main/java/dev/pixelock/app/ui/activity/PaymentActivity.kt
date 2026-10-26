package dev.pixelock.app.ui.activity

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.pixelock.app.BuildConfig
import dev.pixelock.app.R
import dev.pixelock.app.core.PaymentFlow
import dev.pixelock.app.data.remote.BackendException
import dev.pixelock.app.data.remote.OrderPurpose
import dev.pixelock.app.databinding.ActivityPaymentBinding
import dev.pixelock.app.domain.PayRoute
import dev.pixelock.app.domain.isUpiScheme
import dev.pixelock.app.domain.payRoute
import dev.pixelock.app.ui.messageRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows the payment page inside this app, because a browser may be on the block list.
 * Back always leaves. A payment that lands after leaving is picked up the next time the app opens.
 */
class PaymentActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PURPOSE = "purpose"
        private const val EXTRA_PACKAGE = "package"
        private const val POLL_MS = 3_000L
        private const val MAX_WAIT_MS = 20 * 60_000L
        private const val OPEN_DELAY_MS = 600L

        fun intent(context: Context, purpose: String, pkg: String): Intent =
            Intent(context, PaymentActivity::class.java)
                .putExtra(EXTRA_PURPOSE, purpose)
                .putExtra(EXTRA_PACKAGE, pkg)
    }

    private lateinit var binding: ActivityPaymentBinding
    private var pkg = ""

    private val pageRules = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (!request.isForMainFrame && url.scheme == "https") return false
            return when (payRoute(url.scheme, url.host)) {
                PayRoute.STAY -> false
                PayRoute.OPEN_APP -> {
                    openUpiApp(url)
                    true
                }
                PayRoute.REFUSE -> true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.back.setOnClickListener { finish() }

        val purpose = intent.getStringExtra(EXTRA_PURPOSE) ?: OrderPurpose.END_TIMER
        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        binding.title.text = if (purpose == OrderPurpose.UNLOCK_APP) {
            getString(R.string.pay_title_app, appLabel())
        } else {
            getString(R.string.pay_title_timer)
        }
        binding.status.setText(R.string.pay_starting)

        binding.web.settings.apply {
            javaScriptEnabled = true // Razorpay's page needs it.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        binding.web.webViewClient = pageRules

        lifecycleScope.launch { pay(purpose) }
    }

    private suspend fun pay(purpose: String) {
        val order = try {
            PaymentFlow.begin(this, purpose, pkg)
        } catch (e: BackendException) {
            binding.status.setText(e.messageRes())
            return
        }
        if (order == null) {
            finishPaid(purpose)
            return
        }
        val trusted = order.url.startsWith("https://") || BuildConfig.DEBUG
        if (!trusted) {
            binding.status.setText(R.string.err_server)
            return
        }
        binding.web.loadUrl(order.url)
        binding.status.setText(R.string.pay_waiting)

        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val paid = try {
                PaymentFlow.settle(this, order)
            } catch (_: BackendException) {
                false // A dropped connection is no answer. Ask again.
            }
            if (paid) {
                finishPaid(purpose)
                return
            }
        }
        binding.status.setText(R.string.pay_timeout)
    }

    private suspend fun finishPaid(purpose: String) {
        binding.status.setText(R.string.pay_done)
        delay(OPEN_DELAY_MS)
        if (purpose == OrderPurpose.UNLOCK_APP) {
            packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)
        }
        finish()
    }

    /** Hands over to a UPI app. An intent link may only name a UPI scheme, never a component. */
    private fun openUpiApp(url: Uri) {
        val target = if (url.scheme == "intent") {
            runCatching { Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                ?.takeIf { isUpiScheme(it.scheme) }
                ?.let { Intent(Intent.ACTION_VIEW, it.data).setPackage(it.`package`) }
        } else {
            Intent(Intent.ACTION_VIEW, url)
        }
        try {
            startActivity(target?.addCategory(Intent.CATEGORY_BROWSABLE) ?: return)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.pay_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun appLabel(): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun onDestroy() {
        binding.web.destroy()
        super.onDestroy()
    }
}
