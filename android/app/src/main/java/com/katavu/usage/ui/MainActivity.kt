package com.katavu.usage.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.katavu.usage.R
import com.katavu.usage.databinding.ActivityMainBinding
import com.katavu.usage.upload.UploadEntry
import com.katavu.usage.upload.UploadScheduler
import com.katavu.usage.upload.UploadSettings
import com.katavu.usage.upload.UploadStateStore
import com.katavu.usage.upload.UploadStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = RecordingsAdapter(::onEntryClicked)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recordingsList.layoutManager = LinearLayoutManager(this)
        binding.recordingsList.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.recordingsList.adapter = adapter

        binding.enableServiceButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.permissionsButton.setOnClickListener {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
            ))
        }
        binding.configBanner.setOnClickListener { openSettings() }

        // Pick up anything recorded while the app was closed, or left over from earlier failures.
        if (savedInstanceState == null) UploadScheduler.enqueueAllPending(this)

        val store = UploadStateStore.get(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.entries.collect { map ->
                    val items = map.values.sortedByDescending { it.meta.startedAtMillis }
                    adapter.submitList(items)
                    binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.configBanner.visibility =
            if (UploadSettings(this).isConfigured) View.GONE else View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            openSettings()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun onEntryClicked(entry: UploadEntry) {
        if (entry.status == UploadStatus.UPLOADED || entry.status == UploadStatus.UPLOADING) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.retry_upload)
            .setMessage(entry.lastError ?: entry.fileName)
            .setPositiveButton(R.string.retry_upload) { _, _ -> UploadScheduler.retry(this, entry.fileName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
