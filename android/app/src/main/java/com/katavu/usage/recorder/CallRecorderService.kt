package com.katavu.usage.recorder

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRecorder
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.katavu.usage.upload.RecordingFiles
import com.katavu.usage.upload.RecordingMetadata
import com.katavu.usage.upload.UploadScheduler
import java.io.File

/**
 * PLACEHOLDER recorder. Your real CallRecorderService replaces this file; the only things it
 * must add are the two UploadScheduler calls marked "UPLOAD HOOK" below.
 *
 * Follows the phone state and records with the first audio source that starts:
 * VOICE_CALL (CALL), then VOICE_RECOGNITION (VREC), then MIC.
 */
class CallRecorderService : AccessibilityService() {

    private var recorder: MediaRecorder? = null
    private var partFile: File? = null
    private var startedAt = 0L
    private var audioSource = RecordingMetadata.SOURCE_MIC
    private var direction = RecordingMetadata.DIRECTION_OUT
    private var phoneNumber: String? = null
    private var lastState: String = TelephonyManager.EXTRA_STATE_IDLE

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            // Present only when READ_CALL_LOG is granted; Android sends a second broadcast with it.
            @Suppress("DEPRECATION")
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
                ?.let { phoneNumber = it }

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> direction = RecordingMetadata.DIRECTION_IN
                TelephonyManager.EXTRA_STATE_OFFHOOK -> if (recorder == null) {
                    if (lastState == TelephonyManager.EXTRA_STATE_IDLE) direction = RecordingMetadata.DIRECTION_OUT
                    startRecording()
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    stopRecording()
                    phoneNumber = null
                }
            }
            lastState = state
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this, phoneStateReceiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopRecording()
        runCatching { unregisterReceiver(phoneStateReceiver) }
        super.onDestroy()
    }

    private fun startRecording() {
        val dir = RecordingFiles.directory(this) ?: return
        dir.mkdirs()
        startedAt = System.currentTimeMillis()
        // Written as .part and renamed when finished, so a half-written file never looks like a recording.
        val out = File(dir, "recording_$startedAt.m4a.part")

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_CALL to RecordingMetadata.SOURCE_CALL,
            MediaRecorder.AudioSource.VOICE_RECOGNITION to RecordingMetadata.SOURCE_VREC,
            MediaRecorder.AudioSource.MIC to RecordingMetadata.SOURCE_MIC,
        )
        for ((source, label) in sources) {
            @Suppress("DEPRECATION")
            val r = MediaRecorder()
            try {
                r.setAudioSource(source)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44_100)
                r.setAudioEncodingBitRate(64_000)
                r.setOutputFile(out.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                partFile = out
                audioSource = label
                UploadScheduler.onRecordingStarted(out) // UPLOAD HOOK 1
                Log.i(TAG, "Recording with $label")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $label unavailable: ${e.message}")
                r.release()
                out.delete()
            }
        }
        Log.e(TAG, "No audio source could be started")
    }

    private fun stopRecording() {
        val r = recorder ?: return
        val part = partFile
        recorder = null
        partFile = null

        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // stop() throws when nothing was recorded
        } finally {
            r.release()
        }
        if (part == null) return
        if (!ok) {
            part.delete()
            return
        }

        val meta = RecordingMetadata(
            direction = direction,
            phoneNumber = phoneNumber,
            startedAtMillis = startedAt,
            durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt(),
            audioSource = audioSource,
        )
        val finalFile = File(part.parentFile,
            RecordingFiles.fileName(startedAt, direction, phoneNumber, audioSource))
        if (!part.renameTo(finalFile)) {
            Log.e(TAG, "Could not rename ${part.name}")
            return
        }
        UploadScheduler.onRecordingFinished(this, finalFile, meta) // UPLOAD HOOK 2
    }

    companion object {
        private const val TAG = "CallRecorderService"
    }
}
