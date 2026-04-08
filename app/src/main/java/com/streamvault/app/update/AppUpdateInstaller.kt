package com.streamvault.app.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.streamvault.app.BuildConfig
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdateDownloadState(
    val status: AppUpdateDownloadStatus = AppUpdateDownloadStatus.Idle,
    val versionName: String? = null,
    val downloadId: Long? = null
)

enum class AppUpdateDownloadStatus {
    Idle,
    Downloading,
    Downloaded,
    Failed
}

@Singleton
class AppUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloadState = MutableStateFlow(AppUpdateDownloadState())

    val downloadState: StateFlow<AppUpdateDownloadState> = _downloadState.asStateFlow()

    init {
        scope.launch {
            refreshState()
        }
    }

    suspend fun refreshState(): AppUpdateDownloadState = withContext(Dispatchers.IO) {
        val downloadId = preferencesRepository.appUpdateDownloadId.first()
        val downloadedVersionName = preferencesRepository.downloadedAppUpdateVersionName.first()
        val apkFile = downloadedVersionName?.let(::apkFileForVersion)

        if (downloadId == null) {
            val restoredState = if (downloadedVersionName != null && apkFile?.exists() == true) {
                AppUpdateDownloadState(
                    status = AppUpdateDownloadStatus.Downloaded,
                    versionName = downloadedVersionName,
                    downloadId = null
                )
            } else {
                if (downloadedVersionName != null) {
                    preferencesRepository.setDownloadedAppUpdateVersionName(null)
                }
                AppUpdateDownloadState()
            }
            _downloadState.value = restoredState
            return@withContext restoredState
        }

        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                preferencesRepository.setAppUpdateDownloadId(null)
                val fallbackState = if (downloadedVersionName != null && apkFile?.exists() == true) {
                    AppUpdateDownloadState(
                        status = AppUpdateDownloadStatus.Downloaded,
                        versionName = downloadedVersionName
                    )
                } else {
                    preferencesRepository.setDownloadedAppUpdateVersionName(null)
                    AppUpdateDownloadState(status = AppUpdateDownloadStatus.Failed)
                }
                _downloadState.value = fallbackState
                return@withContext fallbackState
            }

            val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val status = cursor.getInt(statusColumn)
            val state = when (status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_RUNNING -> AppUpdateDownloadState(
                    status = AppUpdateDownloadStatus.Downloading,
                    versionName = downloadedVersionName,
                    downloadId = downloadId
                )

                DownloadManager.STATUS_SUCCESSFUL -> {
                    preferencesRepository.setAppUpdateDownloadId(null)
                    AppUpdateDownloadState(
                        status = AppUpdateDownloadStatus.Downloaded,
                        versionName = downloadedVersionName,
                        downloadId = null
                    )
                }

                else -> {
                    preferencesRepository.setAppUpdateDownloadId(null)
                    AppUpdateDownloadState(
                        status = AppUpdateDownloadStatus.Failed,
                        versionName = downloadedVersionName,
                        downloadId = null
                    )
                }
            }
            _downloadState.value = state
            return@withContext state
        }
    }

    suspend fun startDownload(releaseInfo: GitHubReleaseInfo): Result<Unit> = withContext(Dispatchers.IO) {
        val downloadUrl = releaseInfo.downloadUrl
            ?: return@withContext Result.error("Update download is unavailable for this release")

        try {
            val targetFile = apkFileForVersion(releaseInfo.versionName)
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val existingVersion = preferencesRepository.downloadedAppUpdateVersionName.first()
            if (existingVersion != null && existingVersion != releaseInfo.versionName) {
                apkFileForVersion(existingVersion).delete()
            }

            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("StreamVault ${releaseInfo.versionName}")
                .setDescription("Downloading the latest StreamVault update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationUri(Uri.fromFile(targetFile))

            val downloadId = downloadManager.enqueue(request)
            preferencesRepository.setDownloadedAppUpdateVersionName(releaseInfo.versionName)
            preferencesRepository.setAppUpdateDownloadId(downloadId)
            _downloadState.value = AppUpdateDownloadState(
                status = AppUpdateDownloadStatus.Downloading,
                versionName = releaseInfo.versionName,
                downloadId = downloadId
            )
            Result.success(Unit)
        } catch (error: IllegalArgumentException) {
            Result.error("Failed to start update download", error)
        } catch (error: SecurityException) {
            Result.error("Update download requires additional permissions", error)
        }
    }

    suspend fun installDownloadedUpdate(): Result<Unit> = withContext(Dispatchers.IO) {
        val currentState = refreshState()
        if (currentState.status != AppUpdateDownloadStatus.Downloaded || currentState.versionName.isNullOrBlank()) {
            return@withContext Result.error("No downloaded update is ready to install")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return@withContext Result.error("Allow installs from this app, then try Install update again")
        }

        val apkFile = apkFileForVersion(currentState.versionName)
        if (!apkFile.exists()) {
            preferencesRepository.setDownloadedAppUpdateVersionName(null)
            return@withContext Result.error("Downloaded update file is missing")
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (error: ActivityNotFoundException) {
            Result.error("No package installer is available on this device", error)
        } catch (error: SecurityException) {
            Result.error("The package installer could not be launched", error)
        }
    }

    private fun apkFileForVersion(versionName: String): File {
        val sanitizedVersion = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.cacheDir, "downloads")
        return File(downloadsDir, "StreamVault-$sanitizedVersion.apk")
    }
}