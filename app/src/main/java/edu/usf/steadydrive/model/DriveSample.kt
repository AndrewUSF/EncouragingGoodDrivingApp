package edu.usf.steadydrive.model

import java.util.Locale

data class DriveSample(
    val timestampUtc: String,
    val participantId: String,
    val sessionId: String,
    val phase: String,
    val elapsedSeconds: Int,
    val collecting: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val gpsAccuracyMeters: Double?,
    val speedMph: Double?,
    val speedLimitMph: Double?,
    val overLimitMph: Double?,
    val speedingThreePlus: Boolean,
    val heading: Double?,
    val mediaState: String,
    val interventionAction: String,
    val speedLimitSource: String?,
    val speedLimitConfidence: Double?,
    val batteryPercent: Int?,
    val networkState: String,
    val appState: String,
) {
    fun toCsvRow(): String =
        listOf(
            timestampUtc,
            participantId,
            sessionId,
            phase,
            elapsedSeconds.toString(),
            if (collecting) "1" else "0",
            formatCoordinate(latitude),
            formatCoordinate(longitude),
            formatNumber(gpsAccuracyMeters),
            formatNumber(speedMph),
            formatNumber(speedLimitMph),
            formatNumber(overLimitMph),
            if (speedingThreePlus) "1" else "0",
            formatNumber(heading),
            mediaState,
            interventionAction,
            speedLimitSource.orEmpty(),
            formatNumber(speedLimitConfidence),
            batteryPercent?.toString().orEmpty(),
            networkState,
            appState,
        ).joinToString(",") { escapeCsv(it) }

    private fun formatNumber(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()

    // Latitude/longitude need far more precision than the other metrics: at this latitude six
    // decimal places is roughly 0.1 m, whereas the two used for speeds collapses an entire drive
    // onto a single ~1 km coordinate.
    private fun formatCoordinate(value: Double?): String =
        value?.let { String.format(Locale.US, "%.6f", it) }.orEmpty()

    private fun escapeCsv(value: String): String {
        if (!value.contains(",") && !value.contains("\"") && !value.contains("\n")) {
            return value
        }
        return "\"${value.replace("\"", "\"\"")}\""
    }

    companion object {
        const val CSV_HEADER =
            "timestamp_utc,participant_id,session_id,phase,elapsed_s," +
                "collecting_flag,latitude,longitude,gps_accuracy_m,speed_mph,speed_limit_mph," +
                "over_limit_mph,speeding_3plus,heading,media_state,intervention_action," +
                "speed_limit_source,speed_limit_confidence,battery_pct,network_state,app_state"
    }
}
