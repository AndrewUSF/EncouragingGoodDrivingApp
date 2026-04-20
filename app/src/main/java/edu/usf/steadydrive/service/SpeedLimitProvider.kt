package edu.usf.steadydrive.service

import android.location.Location
import edu.usf.steadydrive.data.StudyRepository

data class PostedSpeedLimit(
    val speedLimitMph: Double?,
    val source: String?,
    val confidence: Double?,
)

interface SpeedLimitProvider {
    suspend fun currentPostedSpeedLimit(location: Location?): PostedSpeedLimit
}

class PortalSpeedLimitProvider(
    private val repository: StudyRepository,
) : SpeedLimitProvider {
    private var cachedLocation: Location? = null
    private var cachedSpeedLimit =
        PostedSpeedLimit(
            speedLimitMph = null,
            source = "pending_osm_lookup",
            confidence = 0.0,
        )
    private var lastLookupAtMillis: Long = 0

    override suspend fun currentPostedSpeedLimit(location: Location?): PostedSpeedLimit {
        if (location == null) {
            return cachedSpeedLimit
        }

        val now = System.currentTimeMillis()
        val recentEnough = now - lastLookupAtMillis < LOOKUP_INTERVAL_MILLIS
        val stillNearby =
            cachedLocation?.distanceTo(location)?.let { distance -> distance < MIN_DISTANCE_FOR_REFRESH_METERS }
                ?: false

        if (recentEnough && stillNearby) {
            return cachedSpeedLimit
        }

        val response = repository.lookupSpeedLimit(location.latitude, location.longitude)
        cachedLocation = Location(location)
        lastLookupAtMillis = now

        if (response.speedLimitMph != null || cachedSpeedLimit.speedLimitMph == null) {
            cachedSpeedLimit = response
        }

        return cachedSpeedLimit
    }

    companion object {
        private const val LOOKUP_INTERVAL_MILLIS = 10_000L
        private const val MIN_DISTANCE_FOR_REFRESH_METERS = 120f
    }
}
