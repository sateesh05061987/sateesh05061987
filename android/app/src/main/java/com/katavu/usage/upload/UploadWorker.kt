package com.katavu.usage.upload

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Uploads one recording. Unique work per file; see [UploadScheduler]. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure()
        val store = UploadStateStore.get(applicationContext)
        val entry = store.get(fileName) ?: return@withContext Result.failure()
        if (entry.status == UploadStatus.UPLOADED) return@withContext Result.success()

        val settings = UploadSettings(applicationContext)
        val baseUrl = settings.baseUrl
        if (baseUrl == null || settings.apiKey.isBlank()) {
            // Stay pending: saving the settings re-enqueues everything that is not uploaded.
            store.update(fileName) { it.copy(status = UploadStatus.PENDING, lastError = "Server not configured") }
            return@withContext Result.failure()
        }

        val file = File(RecordingFiles.directory(applicationContext), fileName)
        if (!file.isFile) {
            store.update(fileName) { it.copy(status = UploadStatus.FAILED, lastError = "Local file is missing") }
            return@withContext Result.failure()
        }

        store.update(fileName) { it.copy(status = UploadStatus.UPLOADING, attempts = it.attempts + 1) }

        when (val outcome = RecordingUploader().upload(baseUrl, settings.apiKey, file, entry.meta, deviceName())) {
            is UploadOutcome.Success -> {
                val deleted = settings.deleteAfterUpload && file.delete()
                store.update(fileName) {
                    it.copy(
                        status = UploadStatus.UPLOADED,
                        lastError = null,
                        uploadedAtMillis = System.currentTimeMillis(),
                        remoteId = outcome.remoteId,
                        localDeleted = deleted,
                    )
                }
                Log.i(TAG, "Uploaded $fileName (remote id ${outcome.remoteId}, duplicate=${outcome.duplicate})")
                Result.success()
            }
            is UploadOutcome.Retry -> {
                val giveUp = runAttemptCount + 1 >= MAX_ATTEMPTS
                store.update(fileName) {
                    it.copy(
                        status = if (giveUp) UploadStatus.FAILED else UploadStatus.PENDING,
                        lastError = outcome.message,
                    )
                }
                Log.w(TAG, "Upload of $fileName failed (attempt ${runAttemptCount + 1}): ${outcome.message}")
                if (giveUp) Result.failure() else Result.retry()
            }
            is UploadOutcome.Failure -> {
                store.update(fileName) { it.copy(status = UploadStatus.FAILED, lastError = outcome.message) }
                Log.w(TAG, "Upload of $fileName rejected: ${outcome.message}")
                Result.failure()
            }
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(applicationContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        const val KEY_FILE_NAME = "fileName"
        private const val MAX_ATTEMPTS = 10
        private const val TAG = "UploadWorker"
    }
}
