package edu.usf.steadydrive.model

data class AssignedDeviceConfig(
    val deviceId: String,
    val participantId: String,
    val phase: ParticipantPhase,
    val weeklyRoutineNotes: String?,
    val schedules: List<ScheduledDrive>,
)
