package edu.usf.steadydrive.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import edu.usf.steadydrive.data.StudyRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            StudyRepository(context).scheduleStoredReminders()
        }
    }
}
