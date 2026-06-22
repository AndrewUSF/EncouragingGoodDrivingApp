package edu.usf.steadydrive.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import edu.usf.steadydrive.R
import edu.usf.steadydrive.SteadyDriveApplication
import edu.usf.steadydrive.data.CsvSessionWriter
import edu.usf.steadydrive.data.DownloadsExporter
import edu.usf.steadydrive.data.StudyRepository
import edu.usf.steadydrive.model.AssignedDeviceConfig
import edu.usf.steadydrive.model.DriveSample
import edu.usf.steadydrive.model.DriveSessionSummary
import edu.usf.steadydrive.model.ParticipantPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import java.util.UUID

class DriveSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { StudyRepository(applicationContext) }
    private val downloadsExporter by lazy { DownloadsExporter(applicationContext) }
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(applicationContext)
    }
    private val audioController by lazy { AudioInterventionController(applicationContext) }
    private val speedLimitProvider: SpeedLimitProvider by lazy { PortalSpeedLimitProvider(repository) }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    private var latestLocation: Location? = null
    private lateinit var locationCallback: LocationCallback
    private var sessionJob: Job? = null
    private var speedLimitJob: Job? = null
    private var stopWatcherJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var csvWriter: CsvSessionWriter? = null
    private var startedAt: Instant? = null
    private var participantId: String? = null
    private var phase: ParticipantPhase? = null
    private var sessionId: String? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var currentSpeedLimit =
        PostedSpeedLimit(
            speedLimitMph = null,
            source = "pending_osm_lookup",
            confidence = 0.0,
        )

    private var samplesRecorded = 0
    private var speedSum = 0.0
    private var speedCount = 0
    private var peakSpeedMph = 0.0
    private var maxOverLimitMph = 0.0
    private var speedingSeconds = 0

    override fun onCreate() {
        super.onCreate()
        locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    latestLocation = result.lastLocation ?: latestLocation
                }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                if (sessionJob == null) {
                    startNewSession(intent)
                }
            }

            ACTION_STOP_SESSION -> {
                // A researcher (or the app) asked to end the run early. The session loop checks
                // this flag once per second and finalizes what it has collected so far.
                if (sessionJob != null) {
                    stopRequested = true
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startNewSession(intent: Intent) {
        val phaseValue = intent.getStringExtra(EXTRA_PHASE)
        val resolvedPhase = ParticipantPhase.fromWireValue(phaseValue)
        val resolvedParticipantId = intent.getStringExtra(EXTRA_PARTICIPANT_ID)

        if (resolvedPhase == null || resolvedParticipantId.isNullOrBlank()) {
            stopSelf()
            return
        }

        val resolvedSessionId = UUID.randomUUID().toString()
        resetSessionState(resolvedSessionId)

        phase = resolvedPhase
        participantId = resolvedParticipantId
        startedAt = Instant.now()
        csvWriter = CsvSessionWriter(applicationContext, resolvedParticipantId, resolvedSessionId)

        // Record the active session so the sync loop and the researcher portal can both see that
        // this device is collecting, and so a remote stop can be matched to the right session.
        repository.markSessionActive(
            sessionId = resolvedSessionId,
            phase = resolvedPhase,
            startedAtIso = startedAt?.toString().orEmpty(),
        )

        acquireWakeLock()
        startForeground(
            SESSION_NOTIFICATION_ID,
            buildNotification(TOTAL_SESSION_SECONDS),
        )
        startLocationUpdates()
        startSpeedLimitUpdates()
        startStopWatcher()
        // Clear any mute a previously killed session may have left on the music stream before this
        // run's phase logic takes over.
        audioController.resetToBaseline()
        startSessionLoop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sessionJob?.cancel()
        speedLimitJob?.cancel()
        stopWatcherJob?.cancel()
        stopLocationUpdates()
        audioController.resetToBaseline()
        releaseWakeLock()
        runCatching { csvWriter?.close() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun resetSessionState(resolvedSessionId: String) {
        latestLocation = null
        sessionId = resolvedSessionId
        stopRequested = false
        currentSpeedLimit =
            PostedSpeedLimit(
                speedLimitMph = null,
                source = "pending_osm_lookup",
                confidence = 0.0,
            )
        samplesRecorded = 0
        speedSum = 0.0
        speedCount = 0
        peakSpeedMph = 0.0
        maxOverLimitMph = 0.0
        speedingSeconds = 0
    }

    /**
     * Drives sampling off the wall clock instead of counting [delay] iterations. Even if the device
     * dozes or a tick runs long, each pass recomputes the true elapsed time, so the countdown never
     * falls behind and the session always ends 17 minutes after it started.
     */
    private fun startSessionLoop() {
        val activePhase = phase ?: return
        val activeParticipantId = participantId ?: return
        val activeSessionId = sessionId ?: return
        val writer = csvWriter ?: return
        val startMillis = (startedAt ?: Instant.now()).toEpochMilli()

        sessionJob =
            serviceScope.launch {
                while (true) {
                    val nowMillis = System.currentTimeMillis()
                    val elapsedSeconds = ((nowMillis - startMillis) / 1_000L).toInt().coerceAtLeast(0)

                    if (stopRequested || elapsedSeconds >= TOTAL_SESSION_SECONDS) {
                        break
                    }

                    val remainingSeconds = (TOTAL_SESSION_SECONDS - elapsedSeconds).coerceAtLeast(0)
                    val collecting = elapsedSeconds >= WARMUP_SECONDS
                    val speedLimit = currentSpeedLimit
                    val speedMph = latestLocation?.speed?.toDouble()?.metersPerSecondToMph()
                    val overLimitMph =
                        if (speedMph != null && speedLimit.speedLimitMph != null) {
                            speedMph - speedLimit.speedLimitMph
                        } else {
                            null
                        }
                    val speedingThreePlus = (overLimitMph ?: Double.NEGATIVE_INFINITY) >= 3.0
                    val shouldMuteForSpeeding =
                        activePhase == ParticipantPhase.PHASE_B && speedingThreePlus

                    audioController.update(activePhase, shouldMuteForSpeeding)

                    val sample =
                        DriveSample(
                            timestampUtc = Instant.now().toString(),
                            participantId = activeParticipantId,
                            sessionId = activeSessionId,
                            phase = activePhase.wireValue,
                            elapsedSeconds = elapsedSeconds,
                            collecting = collecting,
                            latitude = latestLocation?.latitude,
                            longitude = latestLocation?.longitude,
                            gpsAccuracyMeters = latestLocation?.accuracy?.toDouble(),
                            speedMph = speedMph,
                            speedLimitMph = speedLimit.speedLimitMph,
                            overLimitMph = overLimitMph,
                            speedingThreePlus = speedingThreePlus,
                            heading = latestLocation?.bearing?.toDouble(),
                            mediaState = audioController.mediaState,
                            interventionAction = audioController.interventionAction,
                            speedLimitSource = speedLimit.source,
                            speedLimitConfidence = speedLimit.confidence,
                            batteryPercent = currentBatteryPercent(),
                            networkState = currentNetworkState(),
                            appState = if (collecting) "active_collection" else "warmup",
                            volumeFixed = audioController.isVolumeFixed,
                            mediaVolumePercent = audioController.musicVolumePercent,
                        )

                    writer.append(sample)
                    updateSummary(sample, collecting)
                    broadcastState(remainingSeconds, false, writer.fileName)
                    updateNotification(remainingSeconds)

                    // Sleep only until the next whole-second boundary relative to the start time, so
                    // any time spent doing the work above does not accumulate into drift.
                    val nextBoundaryMillis = startMillis + (elapsedSeconds + 1).toLong() * 1_000L
                    val sleepMillis = nextBoundaryMillis - System.currentTimeMillis()
                    delay(sleepMillis.coerceIn(1L, 1_000L))
                }

                completeSession(
                    writer = writer,
                    activeSessionId = activeSessionId,
                    activeParticipantId = activeParticipantId,
                    activePhase = activePhase,
                    completionStatus = if (stopRequested) "stopped_early" else "completed",
                )
            }
    }

    private suspend fun completeSession(
        writer: CsvSessionWriter,
        activeSessionId: String,
        activeParticipantId: String,
        activePhase: ParticipantPhase,
        completionStatus: String,
    ) {
        speedLimitJob?.cancel()
        stopWatcherJob?.cancel()
        audioController.resetToBaseline()
        stopLocationUpdates()
        writer.close()

        val endedAt = Instant.now()
        val averageSpeed =
            if (speedCount > 0) {
                speedSum / speedCount
            } else {
                null
            }

        val downloadsFileName =
            withContext(Dispatchers.IO) {
                downloadsExporter.exportCsv(writer.file, writer.fileName)
            }

        repository.enqueueSessionUpload(
            DriveSessionSummary(
                sessionId = activeSessionId,
                participantId = activeParticipantId,
                phase = activePhase.wireValue,
                startedAt = startedAt?.toString().orEmpty(),
                endedAt = endedAt.toString(),
                localFileName = writer.fileName,
                downloadsFileName = downloadsFileName,
                samplesRecorded = samplesRecorded,
                averageSpeedMph = averageSpeed,
                peakSpeedMph = if (peakSpeedMph > 0.0) peakSpeedMph else null,
                maxOverLimitMph = if (maxOverLimitMph > 0.0) maxOverLimitMph else null,
                speedingSeconds = speedingSeconds,
                completionStatus = completionStatus,
            ),
            writer.file.absolutePath,
        )

        // The run is over locally; clear the active-session marker so the next sync reports the
        // device as idle and the portal re-enables phase changes.
        repository.markSessionInactive()

        val displayFileName = downloadsFileName ?: writer.fileName
        broadcastState(0, true, displayFileName)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (canPostNotifications()) {
            notifyWithPermission(
                buildCompletionNotification(
                    fileName = displayFileName,
                    exportedToDownloads = downloadsFileName != null,
                ),
            )
        }

        stopSelf()
    }

    private fun updateSummary(sample: DriveSample, collecting: Boolean) {
        samplesRecorded += 1
        if (!collecting) {
            return
        }

        sample.speedMph?.let { speed ->
            speedSum += speed
            speedCount += 1
            if (speed > peakSpeedMph) {
                peakSpeedMph = speed
            }
        }

        sample.overLimitMph?.let { overLimit ->
            if (overLimit > maxOverLimitMph) {
                maxOverLimitMph = overLimit
            }
            if (overLimit >= 3.0) {
                speedingSeconds += 1
            }
        }
    }

    /**
     * Polls the posted speed limit on its own cadence so the per-second sampling loop never blocks
     * on a network round trip. The provider itself throttles how often it actually calls the portal.
     */
    private fun startSpeedLimitUpdates() {
        speedLimitJob =
            serviceScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val location = latestLocation
                    if (location != null) {
                        runCatching { speedLimitProvider.currentPostedSpeedLimit(location) }
                            .onSuccess { currentSpeedLimit = it }
                    }
                    delay(SPEED_LIMIT_POLL_MILLIS)
                }
            }
    }

    /**
     * Periodically reports that this device is actively collecting and checks whether a researcher
     * has asked the portal to stop the session. Runs independently of the foreground UI so a remote
     * stop is honored even when the participant's screen is off.
     */
    private fun startStopWatcher() {
        stopWatcherJob =
            serviceScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val active = repository.activeSession()
                    if (active != null) {
                        // Reports "running" on the very first pass so the portal can lock phase
                        // changes promptly, then keeps checking for a researcher stop request.
                        val shouldStop =
                            runCatching { repository.reportRunningSessionAndCheckStop(active) }
                                .getOrDefault(false)
                        if (shouldStop) {
                            stopRequested = true
                            break
                        }
                    }
                    delay(STOP_POLL_MILLIS)
                }
            }
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        wakeLock =
            powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    // Time out a little after the longest possible run so a crashed finalize can
                    // never leave the CPU pinned awake indefinitely.
                    runCatching { acquire((TOTAL_SESSION_SECONDS + 120).toLong() * 1_000L) }
                }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        wakeLock = null
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            return
        }

        val locationRequest =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(1_000L)
                .build()

        runCatching { requestLocationUpdatesWithPermission(locationRequest) }
    }

    private fun stopLocationUpdates() {
        runCatching {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun broadcastState(
        remainingSeconds: Int,
        completed: Boolean,
        csvFileName: String,
    ) {
        val intent =
            Intent(ACTION_SESSION_STATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_REMAINING_SECONDS, remainingSeconds)
                putExtra(EXTRA_COMPLETED, completed)
                putExtra(EXTRA_CSV_FILE_NAME, csvFileName)
            }
        sendBroadcast(intent)
    }

    private fun buildNotification(remainingSeconds: Int): Notification =
        NotificationCompat.Builder(this, SteadyDriveApplication.CHANNEL_DRIVE_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.drive_session_notification_title))
            .setContentText(
                "${getString(R.string.drive_session_notification_body)} ${formatRemaining(remainingSeconds)} left.",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun buildCompletionNotification(
        fileName: String,
        exportedToDownloads: Boolean,
    ): Notification {
        val completionText =
            if (exportedToDownloads) {
                "CSV saved to Downloads as $fileName. Uploading automatically."
            } else {
                "CSV saved locally as $fileName. Uploading automatically."
            }

        return NotificationCompat.Builder(this, SteadyDriveApplication.CHANNEL_DRIVE_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Data collection complete")
            .setContentText(completionText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun updateNotification(remainingSeconds: Int) {
        if (canPostNotifications()) {
            notifyWithPermission(buildNotification(remainingSeconds))
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyWithPermission(notification: Notification) {
        NotificationManagerCompat.from(this).notify(SESSION_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdatesWithPermission(locationRequest: LocationRequest) {
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper(),
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun currentBatteryPercent(): Int? {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return null
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }
        return ((level / scale.toFloat()) * 100).toInt()
    }

    private fun currentNetworkState(): String {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return "offline"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "offline"

        return when {
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    private fun Double.metersPerSecondToMph(): Double = this * 2.23693629

    private fun formatRemaining(remainingSeconds: Int): String {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    companion object {
        const val TOTAL_SESSION_SECONDS = 17 * 60
        const val WARMUP_SECONDS = 2 * 60

        const val ACTION_START_SESSION = "edu.usf.steadydrive.action.START_SESSION"
        const val ACTION_STOP_SESSION = "edu.usf.steadydrive.action.STOP_SESSION"
        const val ACTION_SESSION_STATE = "edu.usf.steadydrive.action.SESSION_STATE"
        const val EXTRA_PHASE = "extra_phase"
        const val EXTRA_PARTICIPANT_ID = "extra_participant_id"
        const val EXTRA_REMAINING_SECONDS = "extra_remaining_seconds"
        const val EXTRA_COMPLETED = "extra_completed"
        const val EXTRA_CSV_FILE_NAME = "extra_csv_file_name"

        private const val SESSION_NOTIFICATION_ID = 6001
        private const val SPEED_LIMIT_POLL_MILLIS = 2_000L
        private const val STOP_POLL_MILLIS = 12_000L
        private const val WAKE_LOCK_TAG = "SteadyDrive::DriveSession"

        fun createStartIntent(
            context: Context,
            config: AssignedDeviceConfig,
        ): Intent =
            Intent(context, DriveSessionService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_PARTICIPANT_ID, config.participantId)
                putExtra(EXTRA_PHASE, config.phase.wireValue)
            }

        fun createStopIntent(context: Context): Intent =
            Intent(context, DriveSessionService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
    }
}
