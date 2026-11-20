package com.katavu.usage.recorder

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
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
 *
 * BOTH SIDES: since Android 10 a non-system app can't capture the other party's voice directly
 * (VOICE_CALL is blocked for normal apps). To still get both sides, when it falls back to the mic
 * it forces the call onto the loudspeaker so the mic hears the other party too, and restores the
 * previous audio route when the call ends. Set [RECORD_VIA_SPEAKER] to false to turn that off
 * (then only your own side is recorded).
 */
class CallRecorderService : AccessibilityService() {

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var speakerForced = false
    private var savedSpeakerOn: Boolean? = null
    private var savedCommDevice: AudioDeviceInfo? = null

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
                // VOICE_CALL already carries both sides; for the mic fallbacks, force the speaker
                // so the other party is audible to the mic.
                if (RECORD_VIA_SPEAKER && label != RecordingMetadata.SOURCE_CALL) forceSpeaker()
                UploadScheduler.onRecordingStarted(out) // UPLOAD HOOK 1
                Log.i(TAG, "Recording with $label (speaker=$speakerForced)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $label unavailable: ${e.message}")
                r.release()
                out.delete()
            }
        }
        Log.e(TAG, "No audio source could be started")
    }

    /**
     * Route call audio to the loudspeaker so the mic captures the other party too.
     * Uses both the modern API (setCommunicationDevice, API 31+) and the legacy one
     * (setSpeakerphoneOn): some OEMs — e.g. Motorola — only honour the legacy call.
     */
    private fun forceSpeaker() {
        // Modern route (API 31+).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { speaker ->
                        savedCommDevice = audioManager.communicationDevice
                        if (audioManager.setCommunicationDevice(speaker)) speakerForced = true
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setCommunicationDevice failed: ${e.message}")
        }
        // Legacy route (works on OEMs that ignore the modern one).
        try {
            @Suppress("DEPRECATION")
            if (savedSpeakerOn == null) savedSpeakerOn = audioManager.isSpeakerphoneOn
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            speakerForced = true
        } catch (e: Exception) {
            Log.w(TAG, "setSpeakerphoneOn failed: ${e.message}")
        }
    }

    /** Put the audio route back the way it was before recording. */
    private fun restoreAudioRoute() {
        if (!speakerForced) return
        speakerForced = false
        try {
            @Suppress("DEPRECATION")
            savedSpeakerOn?.let { audioManager.isSpeakerphoneOn = it }
            savedSpeakerOn = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
                savedCommDevice?.let { audioManager.setCommunicationDevice(it) }
                savedCommDevice = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore audio route: ${e.message}")
        }
    }

    private fun stopRecording() {
        restoreAudioRoute()
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

        /** Force the loudspeaker during mic recording so both sides are captured. */
        private const val RECORD_VIA_SPEAKER = true
    }
}
