package edu.usf.steadydrive.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import edu.usf.steadydrive.data.StudyRepository

/**
 * Periodic safety net that re-arms the drive reminders. AlarmManager alarms can be silently cleared
 * by the OS, an OEM battery manager, or an app update; this worker re-applies whatever reminder
 * schedule is stored so reminders keep firing even if the participant never reopens the app. It is a
 * redundant backup to the boot/timezone/package-replaced receiver and the on-launch re-arm.
 */
class ReminderRearmWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { StudyRepository(applicationContext).scheduleStoredReminders() }
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "reminder-rearm"
    }
}
