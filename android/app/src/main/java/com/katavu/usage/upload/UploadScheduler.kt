package com.katavu.usage.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Entry point for the recorder and the UI.
 *
 * Recorder hooks:
 *  - [onRecordingStarted] when a file starts being written (so a scan never uploads a half-written file);
 *  - [onRecordingFinished] once the file is closed.
 */
object UploadScheduler {
    private const val WORK_PREFIX = "upload:"
    const val TAG = "recording-upload"

    /** Ignore files modified more recently than this during a scan; they may still be in use. */
    private const val SETTLE_MILLIS = 30_000L

    @Volatile private var activeRecording: String? = null

    fun onRecordingStarted(file: File) {
        activeRecording = file.name
    }

    fun onRecordingFinished(context: Context, file: File, meta: RecordingMetadata) {
        if (activeRecording == file.name) activeRecording = null
        UploadStateStore.get(context).put(UploadEntry(fileName = file.name, meta = meta))
        enqueue(context, file.name, ExistingWorkPolicy.KEEP)
    }

    /**
     * Registers recordings found on disk that have no entry yet and enqueues everything not
     * uploaded. Called on app start; with [includeFailed] also after the settings are saved.
     */
    fun enqueueAllPending(context: Context, includeFailed: Boolean = false) {
        val store = UploadStateStore.get(context)
        val now = System.currentTimeMillis()

        for (file in RecordingFiles.list(context)) {
            if (file.name == activeRecording || now - file.lastModified() < SETTLE_MILLIS) continue
            if (store.get(file.name) == null) {
                store.put(UploadEntry(fileName = file.name, meta = RecordingFiles.inferMetadata(file)))
            }
        }

        for (entry in store.entries.value.values) {
            when (entry.status) {
                UploadStatus.UPLOADED -> Unit
                UploadStatus.PENDING, UploadStatus.UPLOADING -> enqueue(context, entry.fileName, ExistingWorkPolicy.KEEP)
                UploadStatus.FAILED -> if (includeFailed) retry(context, entry.fileName)
            }
        }
    }

    /** Manual retry from the list, or after fixing the settings. */
    fun retry(context: Context, fileName: String) {
        UploadStateStore.get(context).update(fileName) { it.copy(status = UploadStatus.PENDING, lastError = null) }
        enqueue(context, fileName, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(context: Context, fileName: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(UploadWorker.KEY_FILE_NAME to fileName))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_PREFIX + fileName, policy, request)
    }
}
