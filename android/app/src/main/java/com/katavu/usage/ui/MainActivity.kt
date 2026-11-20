package com.katavu.usage.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.katavu.usage.R
import com.katavu.usage.databinding.ActivityMainBinding
import com.katavu.usage.recorder.CallRecorderService
import com.katavu.usage.upload.StatsClient
import com.katavu.usage.upload.StatsResult
import com.katavu.usage.upload.UploadScheduler
import com.katavu.usage.upload.UploadStateStore
import com.katavu.usage.upload.UploadStatus
import com.katavu.usage.upload.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/** Usage dashboard. The per-call list with delete lives in the web app. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshSetupCard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tileIn.tileLabel.setText(R.string.calls_in)
        binding.tileOut.tileLabel.setText(R.string.calls_out)
        binding.tileMonth.tileLabel.setText(R.string.this_month)

        binding.enableServiceButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.permissionsButton.setOnClickListener { permissionLauncher.launch(permissions) }
        binding.swipe.setColorSchemeResources(R.color.green_deep, R.color.gold)
        binding.swipe.setOnRefreshListener { loadStats() }

        if (savedInstanceState == null) UploadScheduler.enqueueAllPending(this)
        showMessage(getString(R.string.loading))
        loadStats()
    }

    override fun onResume() {
        super.onResume()
        refreshSetupCard()
        updateFooter()
    }

    // ── Setup card ──
    private fun refreshSetupCard() {
        val ready = hasAllPermissions() && isRecorderEnabled()
        binding.setupCard.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun hasAllPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isRecorderEnabled(): Boolean {
        val expected = "$packageName/${CallRecorderService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    // ── Stats ──
    private fun loadStats() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { StatsClient().fetch() }
            binding.swipe.isRefreshing = false
            when (result) {
                is StatsResult.Ok -> render(result.stats)
                is StatsResult.Error -> if (binding.totalCalls.text == "—")
                    showMessage(result.message) else updateFooter() // keep last good data on a refresh error
            }
        }
    }

    private fun render(s: UsageStats) {
        if (s.totalCount == 0) { showMessage(getString(R.string.no_usage)); return }
        binding.message.visibility = View.GONE
        binding.swipe.visibility = View.VISIBLE

        val nf = NumberFormat.getInstance(Locale.getDefault())
        binding.totalCalls.text = nf.format(s.totalCount)
        binding.totalTime.text = formatDuration(s.totalDurationSeconds)

        binding.tileIn.tileValue.text = nf.format(s.incoming)
        binding.tileOut.tileValue.text = nf.format(s.outgoing)
        binding.tileMonth.tileValue.text = nf.format(s.thisMonthCount)
        binding.tileMonth.tileSub.apply {
            text = formatDuration(s.thisMonthDurationSeconds)
            visibility = View.VISIBLE
        }

        binding.chart.setValues(s.perDay.map { it.count })

        binding.topList.removeAllViews()
        binding.topCard.visibility = if (s.topNumbers.isEmpty()) View.GONE else View.VISIBLE
        for (n in s.topNumbers) {
            val row = layoutInflater.inflate(R.layout.top_number_row, binding.topList, false)
            row.findViewById<TextView>(R.id.topNumber).text = n.phoneNumber
            row.findViewById<TextView>(R.id.topMeta).text =
                "${n.count} ${if (n.count == 1) "call" else "calls"} · ${formatDuration(n.durationSeconds)}"
            binding.topList.addView(row)
        }

        updateFooter(s.totalBytes)
    }

    private fun showMessage(text: String) {
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
    }

    /** Footer shows server storage used and the local upload sync state. */
    private fun updateFooter(totalBytes: Long = -1) {
        val parts = mutableListOf<String>()
        if (totalBytes >= 0) parts += "${formatSize(totalBytes)} stored"

        val entries = UploadStateStore.get(this).entries.value.values
        val pending = entries.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING }
        val failed = entries.count { it.status == UploadStatus.FAILED }
        when {
            failed > 0 -> parts += "$failed failed to upload"
            pending > 0 -> parts += "$pending uploading…"
            entries.isNotEmpty() -> parts += "all uploaded"
        }
        binding.footer.text = parts.joinToString(" · ")
    }

    companion object {
        fun formatDuration(totalSeconds: Long): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        }
    }
}
