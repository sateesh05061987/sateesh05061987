package com.example.callrecorder.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.callrecorder.R
import com.example.callrecorder.databinding.ActivitySettingsBinding
import com.example.callrecorder.upload.RecordingUploader
import com.example.callrecorder.upload.UploadScheduler
import com.example.callrecorder.upload.UploadSettings
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: UploadSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settings = UploadSettings(this)
        if (savedInstanceState == null) {
            binding.serverUrl.setText(settings.serverUrl)
            binding.apiKey.setText(settings.apiKey)
            binding.deleteAfterUpload.isChecked = settings.deleteAfterUpload
        }

        binding.saveButton.setOnClickListener { save() }
        binding.testButton.setOnClickListener { testConnection() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Validates the form. Returns the parsed URL and key, or null after showing the errors. */
    private fun validated(): Pair<HttpUrl, String>? {
        val url = UploadSettings.parseServerUrl(binding.serverUrl.text?.toString().orEmpty())
        val key = binding.apiKey.text?.toString()?.trim().orEmpty()
        binding.serverUrlLayout.error = if (url == null) getString(R.string.error_https_required) else null
        binding.apiKeyLayout.error = if (key.isEmpty()) getString(R.string.error_api_key_required) else null
        return if (url != null && key.isNotEmpty()) url to key else null
    }

    private fun save() {
        val (url, key) = validated() ?: return
        settings.serverUrl = url.toString().trimEnd('/')
        settings.apiKey = key
        settings.deleteAfterUpload = binding.deleteAfterUpload.isChecked
        // New settings may fix earlier failures (wrong key / URL), so retry those too.
        UploadScheduler.enqueueAllPending(this, includeFailed = true)
        Snackbar.make(binding.root, R.string.saved, Snackbar.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        val (url, key) = validated() ?: return
        binding.testButton.isEnabled = false
        binding.testResult.text = getString(R.string.testing)
        binding.testResult.setTextColor(binding.serverUrl.currentTextColor)
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) { RecordingUploader().ping(url, key) }
            binding.testButton.isEnabled = true
            binding.testResult.text = problem ?: "Connected to $url"
            binding.testResult.setTextColor(ContextCompat.getColor(this@SettingsActivity,
                if (problem == null) R.color.status_uploaded else R.color.status_failed))
        }
    }
}
