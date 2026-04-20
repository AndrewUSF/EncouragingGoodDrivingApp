package edu.usf.steadydrive.ui

import edu.usf.steadydrive.model.AssignedDeviceConfig

enum class SteadyDriveScreenMode {
    CONFIGURING,
    READY,
    RUNNING,
    COMPLETE,
    ERROR,
}

data class SteadyDriveUiState(
    val screenMode: SteadyDriveScreenMode = SteadyDriveScreenMode.CONFIGURING,
    val assignedConfig: AssignedDeviceConfig? = null,
    val remainingSeconds: Int = 17 * 60,
    val isSyncing: Boolean = true,
    val errorMessage: String? = null,
    val lastCsvFileName: String? = null,
)
