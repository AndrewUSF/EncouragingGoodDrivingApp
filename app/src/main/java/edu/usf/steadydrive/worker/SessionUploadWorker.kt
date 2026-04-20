package edu.usf.steadydrive.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import edu.usf.steadydrive.data.MobileApiClient
import edu.usf.steadydrive.data.StudyRepository
import edu.usf.steadydrive.model.DriveSessionSummary
import java.io.File

class SessionUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = StudyRepository(appContext)

    override suspend fun doWork(): Result {
        val csvFilePath = inputData.getString(KEY_CSV_FILE_PATH) ?: return Result.failure()
        val csvFile = File(csvFilePath)
        if (!csvFile.exists()) {
            return Result.failure()
        }

        val summary =
            DriveSessionSummary(
                sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure(),
                participantId = inputData.getString(KEY_PARTICIPANT_ID) ?: return Result.failure(),
                phase = inputData.getString(KEY_PHASE) ?: return Result.failure(),
                startedAt = inputData.getString(KEY_STARTED_AT) ?: return Result.failure(),
                endedAt = inputData.getString(KEY_ENDED_AT) ?: return Result.failure(),
                localFileName = inputData.getString(KEY_LOCAL_FILE_NAME) ?: csvFile.name,
                downloadsFileName = inputData.getString(KEY_DOWNLOADS_FILE_NAME),
                samplesRecorded = inputData.getString(KEY_SAMPLES_RECORDED)?.toIntOrNull() ?: 0,
                averageSpeedMph = inputData.getString(KEY_AVERAGE_SPEED_MPH)?.toDoubleOrNull(),
                peakSpeedMph = inputData.getString(KEY_PEAK_SPEED_MPH)?.toDoubleOrNull(),
                maxOverLimitMph = inputData.getString(KEY_MAX_OVER_LIMIT_MPH)?.toDoubleOrNull(),
                speedingSeconds = inputData.getString(KEY_SPEEDING_SECONDS)?.toIntOrNull() ?: 0,
                completionStatus =
                    inputData.getString(KEY_COMPLETION_STATUS) ?: DEFAULT_COMPLETION_STATUS,
            )

        return when (repository.uploadSessionPackage(summary, csvFile)) {
            MobileApiClient.UploadResult.Success -> {
                runCatching {
                    if (csvFile.exists()) {
                        csvFile.delete()
                    }
                }
                Result.success()
            }
            is MobileApiClient.UploadResult.PermanentFailure -> Result.failure()
            is MobileApiClient.UploadResult.RetryableFailure -> Result.retry()
        }
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_PARTICIPANT_ID = "participant_id"
        private const val KEY_PHASE = "phase"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_ENDED_AT = "ended_at"
        private const val KEY_LOCAL_FILE_NAME = "local_file_name"
        private const val KEY_DOWNLOADS_FILE_NAME = "downloads_file_name"
        private const val KEY_SAMPLES_RECORDED = "samples_recorded"
        private const val KEY_AVERAGE_SPEED_MPH = "average_speed_mph"
        private const val KEY_PEAK_SPEED_MPH = "peak_speed_mph"
        private const val KEY_MAX_OVER_LIMIT_MPH = "max_over_limit_mph"
        private const val KEY_SPEEDING_SECONDS = "speeding_seconds"
        private const val KEY_COMPLETION_STATUS = "completion_status"
        private const val KEY_CSV_FILE_PATH = "csv_file_path"
        private const val DEFAULT_COMPLETION_STATUS = "completed"

        fun buildInputData(summary: DriveSessionSummary, csvFilePath: String): Data =
            Data.Builder()
                .putString(KEY_SESSION_ID, summary.sessionId)
                .putString(KEY_PARTICIPANT_ID, summary.participantId)
                .putString(KEY_PHASE, summary.phase)
                .putString(KEY_STARTED_AT, summary.startedAt)
                .putString(KEY_ENDED_AT, summary.endedAt)
                .putString(KEY_LOCAL_FILE_NAME, summary.localFileName)
                .putString(KEY_DOWNLOADS_FILE_NAME, summary.downloadsFileName)
                .putString(KEY_SAMPLES_RECORDED, summary.samplesRecorded.toString())
                .putString(KEY_AVERAGE_SPEED_MPH, summary.averageSpeedMph?.toString())
                .putString(KEY_PEAK_SPEED_MPH, summary.peakSpeedMph?.toString())
                .putString(KEY_MAX_OVER_LIMIT_MPH, summary.maxOverLimitMph?.toString())
                .putString(KEY_SPEEDING_SECONDS, summary.speedingSeconds.toString())
                .putString(KEY_COMPLETION_STATUS, summary.completionStatus)
                .putString(KEY_CSV_FILE_PATH, csvFilePath)
                .build()
    }
}
