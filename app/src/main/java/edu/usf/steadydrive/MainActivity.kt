package edu.usf.steadydrive

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.usf.steadydrive.service.DriveSessionService
import edu.usf.steadydrive.ui.SteadyDriveScreen
import edu.usf.steadydrive.ui.SteadyDriveViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SteadyDriveViewModel by viewModels()
    private var receiverRegistered = false
    private var pendingStartAfterPermissionGrant = false

    private val sessionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != DriveSessionService.ACTION_SESSION_STATE) {
                    return
                }

                viewModel.onSessionStateUpdate(
                    remainingSeconds =
                        intent.getIntExtra(
                            DriveSessionService.EXTRA_REMAINING_SECONDS,
                            DriveSessionService.TOTAL_SESSION_SECONDS,
                        ),
                    completed = intent.getBooleanExtra(DriveSessionService.EXTRA_COMPLETED, false),
                    csvFileName = intent.getStringExtra(DriveSessionService.EXTRA_CSV_FILE_NAME),
                )
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val fineLocationGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

            if (fineLocationGranted && pendingStartAfterPermissionGrant) {
                viewModel.startDrive(this)
            } else if (!fineLocationGranted) {
                Toast.makeText(
                    this,
                    "Location permission is required to begin a drive session.",
                    Toast.LENGTH_LONG,
                ).show()
            }

            pendingStartAfterPermissionGrant = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryOptimizationExemption()

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            SteadyDriveScreen(
                uiState = uiState,
                onStartDrive = {
                    val missingPermissions = requiredPermissions().filterNot(::hasPermission)
                    if (missingPermissions.isEmpty()) {
                        viewModel.startDrive(this)
                    } else {
                        pendingStartAfterPermissionGrant = true
                        permissionLauncher.launch(missingPermissions.toTypedArray())
                    }
                },
                onRetry = viewModel::retrySync,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                sessionReceiver,
                IntentFilter(DriveSessionService.ACTION_SESSION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(sessionReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    /**
     * Asks the participant once to exempt SteadyDrive from battery optimization. Reminders (exact
     * alarms) and the in-drive foreground service survive Doze far more reliably when the app is not
     * battery-restricted, which matters on OEMs that aggressively kill background apps. The system
     * only shows the prompt if the app is not already exempt.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }

        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.fromParts("package", packageName, null)
                },
            )
        }
    }

    private fun requiredPermissions(): List<String> =
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
