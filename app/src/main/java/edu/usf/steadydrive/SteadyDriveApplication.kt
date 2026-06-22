package edu.usf.steadydrive

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import edu.usf.steadydrive.worker.ReminderRearmWorker
import java.util.concurrent.TimeUnit

class SteadyDriveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DRIVE_REMINDERS,
                    getString(R.string.drive_reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DRIVE_SESSION,
                    getString(R.string.drive_session_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        scheduleReminderRearmWork()
    }

    // Re-arm the drive reminders on a recurring schedule as a backstop to the alarm/boot/launch paths,
    // so they keep firing even if the OS or an OEM battery manager clears the pending alarms.
    private fun scheduleReminderRearmWork() {
        runCatching {
            val request =
                PeriodicWorkRequestBuilder<ReminderRearmWorker>(3, TimeUnit.HOURS).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                ReminderRearmWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    companion object {
        const val CHANNEL_DRIVE_REMINDERS = "drive_reminders"
        const val CHANNEL_DRIVE_SESSION = "drive_session"
    }
}
