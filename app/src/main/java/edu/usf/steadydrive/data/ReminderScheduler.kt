package edu.usf.steadydrive.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import edu.usf.steadydrive.MainActivity
import edu.usf.steadydrive.model.ScheduledDrive
import edu.usf.steadydrive.service.ReminderReceiver
import java.time.LocalTime
import java.time.ZonedDateTime

class ReminderScheduler(
    private val context: Context,
) {
    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleWeeklyReminders(schedules: List<ScheduledDrive>) {
        cancelAll()
        schedules.forEach { schedule ->
            val startTime = schedule.startTime ?: return@forEach
            if (schedule.reminderIndex !in 0 until MAX_REMINDERS_PER_DAY) {
                return@forEach
            }

            runCatching {
                scheduleReminder(
                    dayOfWeek = schedule.dayOfWeek,
                    reminderIndex = schedule.reminderIndex,
                    startTime = LocalTime.parse(startTime),
                )
            }
        }
    }

    fun cancelAll() {
        repeat(7) { dayIndex ->
            repeat(MAX_REMINDERS_PER_DAY) { reminderIndex ->
                runCatching { alarmManager.cancel(createPendingIntent(dayIndex, reminderIndex)) }
            }
        }
    }

    private fun scheduleReminder(dayOfWeek: Int, reminderIndex: Int, startTime: LocalTime) {
        val now = ZonedDateTime.now()
        val todayIndex = now.dayOfWeek.value % 7
        val daysUntil = (dayOfWeek - todayIndex + 7) % 7
        var reminderTime =
            now.toLocalDate()
                .plusDays(daysUntil.toLong())
                .atTime(startTime)
                .atZone(now.zone)
                .minusMinutes(15)

        if (reminderTime <= now) {
            reminderTime = reminderTime.plusWeeks(1)
        }

        val triggerAtMillis = reminderTime.toInstant().toEpochMilli()
        val operation = createPendingIntent(dayOfWeek, reminderIndex)

        // Schedule with the most reliable mechanism available, falling back if one is unsupported or
        // blocked on this device/OS so a reminder still fires on as many phones as possible:
        //   1. setAlarmClock            — exact, exempt from Doze, needs no exact-alarm permission.
        //   2. setExactAndAllowWhileIdle — exact, needs the exact-alarm permission.
        //   3. setAndAllowWhileIdle     — inexact, last resort so the reminder still eventually fires.
        val scheduled =
            trySetAlarmClock(triggerAtMillis, operation) ||
                trySetExact(triggerAtMillis, operation)

        if (!scheduled) {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            }
        }
    }

    private fun trySetAlarmClock(triggerAtMillis: Long, operation: PendingIntent): Boolean =
        runCatching {
            val showIntent =
                PendingIntent.getActivity(
                    context,
                    SHOW_INTENT_REQUEST_CODE,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                operation,
            )
        }.isSuccess

    private fun trySetExact(triggerAtMillis: Long, operation: PendingIntent): Boolean {
        val canScheduleExact =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
            } else {
                true
            }
        if (!canScheduleExact) {
            return false
        }

        return runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }.isSuccess
    }

    private fun createPendingIntent(dayOfWeek: Int, reminderIndex: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_DAY_OF_WEEK, dayOfWeek)
            putExtra(ReminderReceiver.EXTRA_REMINDER_INDEX, reminderIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + (dayOfWeek * MAX_REMINDERS_PER_DAY) + reminderIndex,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val REQUEST_CODE_BASE = 4000
        private const val SHOW_INTENT_REQUEST_CODE = 4100
        private const val MAX_REMINDERS_PER_DAY = 3
    }
}
