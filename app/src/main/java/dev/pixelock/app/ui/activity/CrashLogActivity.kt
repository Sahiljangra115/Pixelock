package dev.pixelock.app.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.pixelock.app.databinding.ActivityCrashLogBinding
import java.io.File

class CrashLogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivityCrashLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left + root.paddingStart, bars.top, bars.right + root.paddingEnd, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val log = runCatching { File(filesDir, "crash_log.txt").readText().takeLast(20_000) }.getOrDefault("")
        binding.log.text = log
        binding.share.setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log),
                    null
                )
            )
        }
        binding.close.setOnClickListener { finish() }
    }
}
