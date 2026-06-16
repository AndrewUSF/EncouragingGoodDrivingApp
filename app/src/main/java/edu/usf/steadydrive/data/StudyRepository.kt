package edu.usf.steadydrive.data

import android.content.Context
import edu.usf.steadydrive.BuildConfig
import edu.usf.steadydrive.model.AssignedDeviceConfig
import edu.usf.steadydrive.model.DriveSessionSummary
import edu.usf.steadydrive.model.ParticipantPhase
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

    fun markSessionActive(
        sessionId: String,
        phase: ParticipantPhase,
        startedAtIso: String,
    ) {
        preferences.setActiveSession(sessionId, phase, startedAtIso)
    }

    fun markSessionInactive() {
        preferences.clearActiveSession()
    }

    fun activeSession(): StudyPreferences.ActiveSession? = preferences.getActiveSession()

    suspend fun syncWithServer(): SyncResult {
        val credentials = preferences.getInstallationCredentials()
        apiClient.registerDevice(credentials)

        return when (val result = apiClient.fetchConfig(credentials, preferences.getActiveSession())) {
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

    /**
     * Reports that this device is mid-session and returns whether a researcher has requested an
     * early stop from the portal. Used by the foreground service so the stop is honored even when
     * the app UI is not in the foreground.
     */
    suspend fun reportRunningSessionAndCheckStop(
        activeSession: StudyPreferences.ActiveSession,
    ): Boolean {
        val credentials = preferences.getInstallationCredentials()
        return when (val result = apiClient.fetchConfig(credentials, activeSession)) {
            is MobileApiClient.RemoteConfigResult.Assigned -> result.stopRequested
            MobileApiClient.RemoteConfigResult.Pending -> false
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
