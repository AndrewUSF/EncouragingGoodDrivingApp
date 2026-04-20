package edu.usf.steadydrive.data

import android.content.Context
import edu.usf.steadydrive.BuildConfig
import edu.usf.steadydrive.model.AssignedDeviceConfig
import edu.usf.steadydrive.model.DriveSessionSummary
import edu.usf.steadydrive.service.PostedSpeedLimit
import java.io.File

class StudyRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = StudyPreferences(appContext)
    private val apiClient = MobileApiClient(BuildConfig.PORTAL_BASE_URL)
    private val reminderScheduler = ReminderScheduler(appContext)
    private val sessionUploadScheduler = SessionUploadScheduler(appContext)

    fun installationCredentials(): StudyPreferences.InstallationCredentials =
        preferences.getInstallationCredentials()

    fun loadStoredConfig(): AssignedDeviceConfig? = preferences.loadAssignedConfig()

    fun scheduleStoredReminders() {
        preferences.loadAssignedConfig()?.let { config ->
            reminderScheduler.scheduleWeeklyReminders(config.schedules)
        }
    }

    fun clearAssignedStudyState() {
        reminderScheduler.cancelAll()
        preferences.clearAssignedConfig()
    }

    suspend fun syncWithServer(): SyncResult {
        val credentials = preferences.getInstallationCredentials()
        apiClient.registerDevice(credentials)

        return when (val result = apiClient.fetchConfig(credentials)) {
            MobileApiClient.RemoteConfigResult.Pending -> {
                clearAssignedStudyState()
                SyncResult.Pending
            }

            is MobileApiClient.RemoteConfigResult.Assigned -> {
                preferences.saveAssignedConfig(result.config)
                reminderScheduler.scheduleWeeklyReminders(result.config.schedules)
                SyncResult.Assigned(result.config)
            }
        }
    }

    fun enqueueSessionUpload(summary: DriveSessionSummary, csvFilePath: String) {
        sessionUploadScheduler.enqueue(summary, csvFilePath)
    }

    suspend fun uploadSessionPackage(
        summary: DriveSessionSummary,
        csvFile: File,
    ): MobileApiClient.UploadResult =
        apiClient.uploadSessionPackage(
            credentials = preferences.getInstallationCredentials(),
            summary = summary,
            csvFile = csvFile,
        )

    suspend fun lookupSpeedLimit(latitude: Double, longitude: Double): PostedSpeedLimit =
        apiClient.lookupSpeedLimit(
            credentials = preferences.getInstallationCredentials(),
            latitude = latitude,
            longitude = longitude,
        )

    sealed interface SyncResult {
        data object Pending : SyncResult
        data class Assigned(val config: AssignedDeviceConfig) : SyncResult
    }
}
