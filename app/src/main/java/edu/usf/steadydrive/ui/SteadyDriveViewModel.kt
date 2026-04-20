package edu.usf.steadydrive.ui

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.usf.steadydrive.data.StudyRepository
import edu.usf.steadydrive.service.DriveSessionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SteadyDriveViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = StudyRepository(application)
    private val _uiState =
        MutableStateFlow(
            repository.loadStoredConfig()?.let { config ->
                SteadyDriveUiState(
                    screenMode = SteadyDriveScreenMode.READY,
                    assignedConfig = config,
                    isSyncing = true,
                )
            } ?: SteadyDriveUiState(),
        )
    val uiState: StateFlow<SteadyDriveUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        startSyncLoop()
    }

    fun retrySync() {
        startSyncLoop()
    }

    fun startDrive(context: Context) {
        val config = _uiState.value.assignedConfig ?: return
        ContextCompat.startForegroundService(
            context,
            DriveSessionService.createStartIntent(context, config),
        )
        _uiState.value =
            _uiState.value.copy(
                screenMode = SteadyDriveScreenMode.RUNNING,
                remainingSeconds = DriveSessionService.TOTAL_SESSION_SECONDS,
                errorMessage = null,
            )
    }

    fun onSessionStateUpdate(
        remainingSeconds: Int,
        completed: Boolean,
        csvFileName: String?,
    ) {
        _uiState.value =
            if (completed) {
                _uiState.value.copy(
                    screenMode = SteadyDriveScreenMode.COMPLETE,
                    remainingSeconds = 0,
                    lastCsvFileName = csvFileName,
                    errorMessage = null,
                )
            } else {
                _uiState.value.copy(
                    screenMode = SteadyDriveScreenMode.RUNNING,
                    remainingSeconds = remainingSeconds,
                    errorMessage = null,
                )
            }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob =
            viewModelScope.launch {
                while (true) {
                    try {
                        when (val result = repository.syncWithServer()) {
                            StudyRepository.SyncResult.Pending -> {
                                val nextMode =
                                    if (_uiState.value.screenMode == SteadyDriveScreenMode.RUNNING) {
                                        SteadyDriveScreenMode.RUNNING
                                    } else {
                                        SteadyDriveScreenMode.CONFIGURING
                                    }

                                _uiState.value =
                                    _uiState.value.copy(
                                        screenMode = nextMode,
                                        assignedConfig = null,
                                        isSyncing = true,
                                        errorMessage = null,
                                    )
                                delay(10_000)
                            }

                            is StudyRepository.SyncResult.Assigned -> {
                                val currentMode = _uiState.value.screenMode
                                _uiState.value =
                                    _uiState.value.copy(
                                        screenMode =
                                            when (currentMode) {
                                                SteadyDriveScreenMode.RUNNING,
                                                SteadyDriveScreenMode.COMPLETE,
                                                ->
                                                    currentMode

                                                else -> SteadyDriveScreenMode.READY
                                            },
                                        assignedConfig = result.config,
                                        isSyncing = false,
                                        errorMessage = null,
                                    )
                                delay(15_000)
                            }
                        }
                    } catch (error: Exception) {
                        val existingConfig = repository.loadStoredConfig()
                        val currentMode = _uiState.value.screenMode
                        _uiState.value =
                            if (currentMode == SteadyDriveScreenMode.RUNNING) {
                                _uiState.value.copy(
                                    screenMode = SteadyDriveScreenMode.RUNNING,
                                    isSyncing = false,
                                    errorMessage = null,
                                )
                            } else if (existingConfig != null) {
                                _uiState.value.copy(
                                    screenMode =
                                        when (currentMode) {
                                            SteadyDriveScreenMode.COMPLETE -> currentMode
                                            else -> SteadyDriveScreenMode.READY
                                        },
                                    assignedConfig = existingConfig,
                                    isSyncing = false,
                                    errorMessage = null,
                                )
                            } else {
                                _uiState.value.copy(
                                    screenMode = SteadyDriveScreenMode.ERROR,
                                    assignedConfig = null,
                                    isSyncing = false,
                                    errorMessage =
                                        error.message ?: "Unable to reach the Admin Portal.",
                                )
                            }
                        delay(15_000)
                    }
                }
            }
    }
}
