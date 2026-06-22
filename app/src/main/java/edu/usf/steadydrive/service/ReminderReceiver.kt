package edu.usf.steadydrive.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import edu.usf.steadydrive.MainActivity
import edu.usf.steadydrive.R
import edu.usf.steadydrive.SteadyDriveApplication
import edu.usf.steadydrive.data.StudyRepository

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dayOfWeek = intent.getIntExtra(EXTRA_DAY_OF_WEEK, 0)
        val reminderIndex = intent.getIntExtra(EXTRA_REMINDER_INDEX, 0)
        val launchIntent = Intent(context, MainActivity::class.java)
        val contentIntent =
            PendingIntent.getActivity(
                context,
                5001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, SteadyDriveApplication.CHANNEL_DRIVE_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.drive_reminder_title))
                .setContentText(context.getString(R.string.drive_reminder_body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(contentIntent)
                .build()

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat
                .from(context)
                .notify(NOTIFICATION_ID_BASE + (dayOfWeek * MAX_REMINDERS_PER_DAY) + reminderIndex, notification)
        }
        StudyRepository(context).scheduleStoredReminders()
    }

    companion object {
        const val EXTRA_DAY_OF_WEEK = "extra_day_of_week"
        const val EXTRA_REMINDER_INDEX = "extra_reminder_index"

        private const val NOTIFICATION_ID_BASE = 5000
        private const val MAX_REMINDERS_PER_DAY = 3
    }
}
