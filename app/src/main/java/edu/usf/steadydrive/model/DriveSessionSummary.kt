package edu.usf.steadydrive.model

data class DriveSessionSummary(
    val sessionId: String,
    val participantId: String,
    val phase: String,
    val startedAt: String,
    val endedAt: String,
    val localFileName: String,
    val downloadsFileName: String?,
    val samplesRecorded: Int,
    val averageSpeedMph: Double?,
    val peakSpeedMph: Double?,
    val maxOverLimitMph: Double?,
    val speedingSeconds: Int,
    val completionStatus: String,
)
