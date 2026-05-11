package com.streamvault.app.ui.screens.settings

import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.DriveSyncError
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Thin wrapper around [DriveBackupSyncManager] that mutates [SettingsUiState] so
 * the existing Settings screen can reuse its sync banner + user message machinery.
 *
 * Sign-In itself is delegated to the caller (the screen has the
 * `ActivityResultLauncher`) — this class only:
 *  1. Hands over the intent to launch
 *  2. Forwards the result back into the manager
 *  3. Maintains `uiState.driveAuthState` / `driveSyncStatus` / `driveIsBusy`.
 */
internal class SettingsDriveBackupActions(
    private val driveBackupSyncManager: DriveBackupSyncManager,
    private val backupManager: BackupManager,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val scope: CoroutineScope,
) {
    init {
        scope.launch {
            driveBackupSyncManager.authState.collect { authState ->
                uiState.update { it.copy(driveAuthState = authState) }
            }
        }
        scope.launch {
            driveBackupSyncManager.syncStatus.collect { status ->
                uiState.update { it.copy(driveSyncStatus = status) }
            }
        }
    }

    /**
     * Returns the platform Sign-In intent or `null` if Play Services is missing.
     * Side-effect: surfaces an error message in [SettingsUiState] when relevant.
     */
    suspend fun beginSignIn(): android.content.Intent? {
        uiState.update { it.copy(driveIsBusy = true) }
        return when (val result = driveBackupSyncManager.beginSignIn()) {
            is Result.Success -> {
                uiState.update { it.copy(driveIsBusy = false) }
                result.data.intent as? android.content.Intent
            }
            is Result.Error -> {
                uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        userMessage = mapErrorMessage(result.message),
                    )
                }
                null
            }
            Result.Loading -> null
        }
    }

    fun completeSignIn(intentData: android.content.Intent?) {
        scope.launch {
            uiState.update { it.copy(driveIsBusy = true) }
            val result = driveBackupSyncManager.completeSignIn(intentData)
            uiState.update {
                it.copy(
                    driveIsBusy = false,
                    userMessage = when (result) {
                        is Result.Success -> "Signed in as ${result.data.email ?: result.data.displayName ?: "Drive"}"
                        is Result.Error -> mapErrorMessage(result.message)
                        Result.Loading -> it.userMessage
                    },
                )
            }
        }
    }

    fun signOut() {
        scope.launch {
            uiState.update { it.copy(driveIsBusy = true) }
            driveBackupSyncManager.signOut()
            uiState.update { it.copy(driveIsBusy = false, userMessage = "Drive sign-out OK") }
        }
    }

    fun pushBackup() {
        scope.launch {
            uiState.update { it.copy(driveIsBusy = true) }
            val result = driveBackupSyncManager.pushBackup()
            uiState.update {
                it.copy(
                    driveIsBusy = false,
                    userMessage = when (result) {
                        is Result.Success -> "Backup pushed to Drive"
                        is Result.Error -> mapErrorMessage(result.message)
                        Result.Loading -> it.userMessage
                    },
                )
            }
        }
    }

    /**
     * Downloads the latest remote backup and feeds it into the existing
     * `inspectBackup` flow — preview dialog + conflict resolution are unchanged.
     */
    fun pullBackup() {
        scope.launch {
            uiState.update { it.copy(driveIsBusy = true, isSyncing = true) }
            val pull = driveBackupSyncManager.pullBackup()
            if (pull is Result.Error) {
                uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        isSyncing = false,
                        userMessage = mapErrorMessage(pull.message),
                    )
                }
                return@launch
            }
            val artifact = (pull as Result.Success).data
            val inspect = backupManager.inspectBackup(artifact.localUriString)
            uiState.update { state ->
                when (inspect) {
                    is Result.Success -> state.copy(
                        driveIsBusy = false,
                        isSyncing = false,
                        pendingBackupUri = artifact.localUriString,
                        backupPreview = inspect.data,
                    )
                    is Result.Error -> state.copy(
                        driveIsBusy = false,
                        isSyncing = false,
                        userMessage = "Import failed: ${inspect.message}",
                    )
                    Result.Loading -> state
                }
            }
        }
    }

    private fun mapErrorMessage(code: String): String = when (code) {
        DriveSyncError.PLAY_SERVICES_UNAVAILABLE -> "Google Play Services required"
        DriveSyncError.NOT_SIGNED_IN -> "Sign in to Drive first"
        DriveSyncError.NO_REMOTE_BACKUP -> "No backup on Drive yet — push first"
        DriveSyncError.NETWORK -> "Drive network error"
        DriveSyncError.AUTH_FAILED -> "Drive authentication failed"
        DriveSyncError.EXPORT_FAILED -> "Local export failed"
        DriveSyncError.IMPORT_FAILED -> "Local import failed"
        else -> code
    }
}
