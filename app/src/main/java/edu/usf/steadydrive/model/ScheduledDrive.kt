package edu.usf.steadydrive.model

data class ScheduledDrive(
    val dayOfWeek: Int,
    val reminderIndex: Int = 0,
    val startTime: String?,
)
