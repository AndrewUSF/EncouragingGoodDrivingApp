package edu.usf.steadydrive.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import edu.usf.steadydrive.model.DriveSessionSummary
import edu.usf.steadydrive.worker.SessionUploadWorker
import java.util.concurrent.TimeUnit

class SessionUploadScheduler(
    private val context: Context,
) {
    fun enqueue(summary: DriveSessionSummary, csvFilePath: String) {
        val request =
            OneTimeWorkRequestBuilder<SessionUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .setInputData(SessionUploadWorker.buildInputData(summary, csvFilePath))
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "session-upload-${summary.sessionId}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
