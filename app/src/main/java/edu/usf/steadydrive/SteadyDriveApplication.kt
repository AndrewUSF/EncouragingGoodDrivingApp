package edu.usf.steadydrive

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

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
    }

    companion object {
        const val CHANNEL_DRIVE_REMINDERS = "drive_reminders"
        const val CHANNEL_DRIVE_SESSION = "drive_session"
    }
}
