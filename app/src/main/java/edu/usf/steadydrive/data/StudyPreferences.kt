package edu.usf.steadydrive.data

import android.content.Context
import edu.usf.steadydrive.model.AssignedDeviceConfig
import edu.usf.steadydrive.model.ParticipantPhase
import edu.usf.steadydrive.model.ScheduledDrive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class StudyPreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    fun getInstallationCredentials(): InstallationCredentials {
        val installationId =
            preferences.getString(KEY_INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_INSTALLATION_ID, it).apply()
            }

        val deviceSecret =
            preferences.getString(KEY_DEVICE_SECRET, null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_DEVICE_SECRET, it).apply()
            }

        return InstallationCredentials(installationId = installationId, deviceSecret = deviceSecret)
    }

    fun saveAssignedConfig(config: AssignedDeviceConfig) {
        val scheduleJson = JSONArray()
        config.schedules.forEach { schedule ->
            val scheduleObject = JSONObject()
                .put("dayOfWeek", schedule.dayOfWeek)
                .put("startTime", schedule.startTime)
            scheduleJson.put(scheduleObject)
        }

        preferences.edit()
            .putString(KEY_DEVICE_ID, config.deviceId)
            .putString(KEY_PARTICIPANT_ID, config.participantId)
            .putString(KEY_PHASE, config.phase.wireValue)
            .putString(KEY_WEEKLY_NOTES, config.weeklyRoutineNotes)
            .putString(KEY_SCHEDULES, scheduleJson.toString())
            .apply()
    }

    fun loadAssignedConfig(): AssignedDeviceConfig? {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val participantId = preferences.getString(KEY_PARTICIPANT_ID, null) ?: return null
        val phase =
            ParticipantPhase.fromWireValue(preferences.getString(KEY_PHASE, null)) ?: return null

        val schedulesJson = preferences.getString(KEY_SCHEDULES, "[]").orEmpty()
        val schedulesArray = JSONArray(schedulesJson)
        val schedules = buildList {
            for (index in 0 until schedulesArray.length()) {
                val scheduleObject = schedulesArray.getJSONObject(index)
                add(
                    ScheduledDrive(
                        dayOfWeek = scheduleObject.getInt("dayOfWeek"),
                        startTime =
                            if (scheduleObject.isNull("startTime")) {
                                null
                            } else {
                                scheduleObject.getString("startTime").ifBlank { null }
                            },
                    ),
                )
            }
        }

        return AssignedDeviceConfig(
            deviceId = deviceId,
            participantId = participantId,
            phase = phase,
            weeklyRoutineNotes = preferences.getString(KEY_WEEKLY_NOTES, null),
            schedules = schedules,
        )
    }

    fun clearAssignedConfig() {
        preferences.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PARTICIPANT_ID)
            .remove(KEY_PHASE)
            .remove(KEY_WEEKLY_NOTES)
            .remove(KEY_SCHEDULES)
            .apply()
    }

    fun setActiveSession(
        sessionId: String,
        phase: ParticipantPhase,
        startedAtIso: String,
    ) {
        preferences.edit()
            .putString(KEY_ACTIVE_SESSION_ID, sessionId)
            .putString(KEY_ACTIVE_SESSION_PHASE, phase.wireValue)
            .putString(KEY_ACTIVE_SESSION_STARTED_AT, startedAtIso)
            .apply()
    }

    fun clearActiveSession() {
        preferences.edit()
            .remove(KEY_ACTIVE_SESSION_ID)
            .remove(KEY_ACTIVE_SESSION_PHASE)
            .remove(KEY_ACTIVE_SESSION_STARTED_AT)
            .apply()
    }

    fun getActiveSession(): ActiveSession? {
        val sessionId = preferences.getString(KEY_ACTIVE_SESSION_ID, null) ?: return null
        val phase =
            ParticipantPhase.fromWireValue(preferences.getString(KEY_ACTIVE_SESSION_PHASE, null))
                ?: return null
        val startedAtIso = preferences.getString(KEY_ACTIVE_SESSION_STARTED_AT, null) ?: return null
        return ActiveSession(sessionId = sessionId, phase = phase, startedAtIso = startedAtIso)
    }

    data class InstallationCredentials(
        val installationId: String,
        val deviceSecret: String,
    )

    data class ActiveSession(
        val sessionId: String,
        val phase: ParticipantPhase,
        val startedAtIso: String,
    )

    companion object {
        private const val PREFERENCE_FILE = "steady_drive_preferences"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_DEVICE_SECRET = "device_secret"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PARTICIPANT_ID = "participant_id"
        private const val KEY_PHASE = "phase"
        private const val KEY_WEEKLY_NOTES = "weekly_notes"
        private const val KEY_SCHEDULES = "schedules"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val KEY_ACTIVE_SESSION_PHASE = "active_session_phase"
        private const val KEY_ACTIVE_SESSION_STARTED_AT = "active_session_started_at"
    }
}
