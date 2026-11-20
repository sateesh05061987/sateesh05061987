package com.katavu.usage.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.katavu.usage.R
import com.katavu.usage.databinding.ActivityMainBinding
import com.katavu.usage.databinding.SectionCardBinding
import com.katavu.usage.recorder.CallRecorderService
import com.katavu.usage.upload.KatavuActivity
import com.katavu.usage.upload.KatavuLogin
import com.katavu.usage.upload.KatavuResult
import com.katavu.usage.upload.KatavuUsage
import com.katavu.usage.upload.KatavuUsageClient
import com.katavu.usage.upload.KatavuUser
import com.katavu.usage.upload.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Dashboard of KATAVU app usage: who uses KATAVU, their last login and whether they're signed in,
 * recent logins and recent activity. The call recorder keeps running in the background; its
 * recordings are managed in the web app, not shown here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
    )
    // Only prompt for the recorder once per visit, so it never nags; it clears once enabled.
    private var recorderPrompted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { maybePromptRecorder() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // No setup card: if access is missing (e.g. after a re-install), request it automatically.
        if (!hasAllPermissions()) permissionLauncher.launch(permissions)

        binding.tileToday.tileLabel.setText(R.string.logins_today)
        binding.tileWeek.tileLabel.setText(R.string.last_7_days)
        binding.tileOrgs.tileLabel.setText(R.string.organizations)
        binding.usersSection.sectionTitle.setText(R.string.users)
        binding.loginsSection.sectionTitle.setText(R.string.recent_logins)
        binding.activitySection.sectionTitle.setText(R.string.recent_activity)

        binding.swipe.setColorSchemeResources(R.color.green_deep, R.color.gold)
        binding.swipe.setOnRefreshListener { loadUsage() }

        if (savedInstanceState == null) UploadScheduler.enqueueAllPending(this)
        showMessage(getString(R.string.loading))
        loadUsage()
    }

    override fun onResume() {
        super.onResume()
        maybePromptRecorder()
    }

    // ── Recorder access (no card; prompt only when something is off) ──
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

    /** When the recorder is off, show a one-time prompt that opens Accessibility settings. */
    private fun maybePromptRecorder() {
        if (!hasAllPermissions()) return // let the permission dialog finish first
        if (isRecorderEnabled()) { recorderPrompted = false; return }
        if (recorderPrompted) return
        recorderPrompted = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recorder_off_title)
            .setMessage(R.string.recorder_off_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    // ── Usage ──
    private fun loadUsage() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { KatavuUsageClient().fetch() }
            binding.swipe.isRefreshing = false
            when (result) {
                is KatavuResult.Ok -> render(result.usage)
                is KatavuResult.Error ->
                    if (binding.signedIn.text == "—") showMessage(result.message) // keep last good data otherwise
            }
        }
    }

    private fun render(u: KatavuUsage) {
        binding.message.visibility = View.GONE

        binding.signedIn.text = u.signedIn.toString()
        binding.signedInSub.text = "of ${u.totalUsers} users ${getString(R.string.signed_in_now)}"
        binding.tileToday.tileValue.text = u.loginsToday.toString()
        binding.tileWeek.tileValue.text = u.logins7d.toString()
        binding.tileOrgs.tileValue.text = u.organizations.toString()

        fillUsers(binding.usersSection, u.users)
        fillLogins(binding.loginsSection, u.recentLogins)
        fillActivity(binding.activitySection, u.recentActivity)
    }

    private fun fillUsers(section: SectionCardBinding, users: List<KatavuUser>) {
        val rows = section.sectionRows
        rows.removeAllViews()
        section.root.visibility = if (users.isEmpty()) View.GONE else View.VISIBLE
        for (u in users) {
            val row = inflate(rows, R.layout.item_user_row)
            row.findViewById<View>(R.id.dot).setBackgroundResource(
                if (u.signedIn) R.drawable.dot_online else R.drawable.dot_offline)
            row.findViewById<TextView>(R.id.rowTitle).text = u.name
            row.findViewById<TextView>(R.id.rowSubtitle).text =
                listOfNotNull(u.role, u.organization).joinToString(" · ").ifEmpty { u.username }
            row.findViewById<TextView>(R.id.rowMeta).text =
                if (u.signedIn) getString(R.string.online) else relative(u.lastLoginAt)
            rows.addView(row)
        }
    }

    private fun fillLogins(section: SectionCardBinding, logins: List<KatavuLogin>) {
        val rows = section.sectionRows
        rows.removeAllViews()
        section.root.visibility = if (logins.isEmpty()) View.GONE else View.VISIBLE
        for (l in logins) {
            val row = inflate(rows, R.layout.item_event_row)
            row.findViewById<TextView>(R.id.rowTitle).text = "${l.name} · ${l.action}"
            row.findViewById<TextView>(R.id.rowSubtitle).text =
                listOfNotNull(l.role, l.organization).joinToString(" · ")
            row.findViewById<TextView>(R.id.rowMeta).text = relative(l.at)
            rows.addView(row)
        }
    }

    private fun fillActivity(section: SectionCardBinding, activity: List<KatavuActivity>) {
        val rows = section.sectionRows
        rows.removeAllViews()
        section.root.visibility = if (activity.isEmpty()) View.GONE else View.VISIBLE
        for (a in activity) {
            val row = inflate(rows, R.layout.item_event_row)
            row.findViewById<TextView>(R.id.rowTitle).text = "${a.action} ${a.entity}"
            row.findViewById<TextView>(R.id.rowSubtitle).text =
                listOfNotNull(a.user, a.organization).joinToString(" · ")
            row.findViewById<TextView>(R.id.rowMeta).text = relative(a.at)
            rows.addView(row)
        }
    }

    private fun inflate(parent: ViewGroup, layout: Int): View =
        LayoutInflater.from(this).inflate(layout, parent, false)

    private fun showMessage(text: String) {
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
    }

    /** ISO-8601 UTC timestamp → "2 days ago", or "never" when null/unparseable. */
    private fun relative(iso: String?): String {
        if (iso.isNullOrBlank()) return getString(R.string.never)
        return try {
            val millis = Instant.parse(iso).toEpochMilli()
            DateUtils.getRelativeTimeSpanString(
                millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } catch (e: Exception) {
            getString(R.string.never)
        }
    }
}
