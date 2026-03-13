package com.streamvault.data.manager

import android.content.Context
import android.os.StatFs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

@Singleton
class RecordingManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val okHttpClient: OkHttpClient
) : RecordingManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFile by lazy { File(recordingsDir, "recordings_state.json") }
    private val recordingsDir by lazy { File(context.filesDir, "recordings").apply { mkdirs() } }

    private val itemsState = MutableStateFlow(loadState())
    private val storageState = MutableStateFlow(readStorageState())
    private val stateMutex = Mutex()
    private val activeJobs = mutableMapOf<String, Job>()

    init {
        scope.launch {
            while (isActive) {
                processSchedules()
                storageState.value = readStorageState()
                delay(15_000L)
            }
        }
    }

    override fun observeRecordingItems(): Flow<List<RecordingItem>> = itemsState.asStateFlow()

    override fun observeStorageState(): Flow<RecordingStorageState> = storageState.asStateFlow()

    override suspend fun startManualRecording(request: RecordingRequest): Result<RecordingItem> = withContext(Dispatchers.IO) {
        if (isAdaptiveStream(request.streamUrl)) {
            return@withContext Result.error("Live recording currently supports direct stream URLs only. Adaptive streams are not recordable yet.")
        }
        if (!storageState.value.isWritable) {
            return@withContext Result.error("Recording storage is not writable.")
        }

        val item = RecordingItem(
            id = UUID.randomUUID().toString(),
            providerId = request.providerId,
            channelId = request.channelId,
            channelName = request.channelName,
            streamUrl = request.streamUrl,
            scheduledStartMs = System.currentTimeMillis(),
            scheduledEndMs = request.scheduledEndMs,
            programTitle = request.programTitle,
            outputPath = request.outputPath ?: buildOutputFile(request).absolutePath,
            status = RecordingStatus.RECORDING
        )
        appendItem(item)
        startCapture(item)
        storageState.value = readStorageState()
        Result.success(item)
    }

    override suspend fun scheduleRecording(request: RecordingRequest): Result<RecordingItem> = withContext(Dispatchers.IO) {
        val item = RecordingItem(
            id = UUID.randomUUID().toString(),
            providerId = request.providerId,
            channelId = request.channelId,
            channelName = request.channelName,
            streamUrl = request.streamUrl,
            scheduledStartMs = request.scheduledStartMs,
            scheduledEndMs = request.scheduledEndMs,
            programTitle = request.programTitle,
            outputPath = request.outputPath ?: buildOutputFile(request).absolutePath,
            status = RecordingStatus.SCHEDULED
        )
        appendItem(item)
        Result.success(item)
    }

    override suspend fun stopRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        removeActiveJob(recordingId)?.cancel()
        val item = getItem(recordingId)
            ?: return@withContext Result.error("Recording not found")
        val fileLength = item.outputPath?.let { path -> File(path).takeIf { it.exists() }?.length() } ?: 0L
        updateItem(recordingId) {
            it.copy(
                status = if (fileLength > 0L) RecordingStatus.COMPLETED else RecordingStatus.CANCELLED,
                scheduledEndMs = System.currentTimeMillis()
            )
        }
        Result.success(Unit)
    }

    override suspend fun cancelRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        removeActiveJob(recordingId)?.cancel()
        updateItem(recordingId) { it.copy(status = RecordingStatus.CANCELLED) }
        Result.success(Unit)
    }

    private suspend fun processSchedules() {
        val now = System.currentTimeMillis()
        val snapshot = stateMutex.withLock { itemsState.value }

        snapshot
            .filter { it.status == RecordingStatus.SCHEDULED && it.scheduledStartMs <= now }
            .forEach { scheduled ->
                if (isAdaptiveStream(scheduled.streamUrl)) {
                    updateItemIf(scheduled.id, expectedStatus = RecordingStatus.SCHEDULED) {
                        it.copy(
                            status = RecordingStatus.FAILED,
                            failureReason = "Scheduled recording supports direct stream URLs only."
                        )
                    }
                } else {
                    val updated = updateItemIf(scheduled.id, expectedStatus = RecordingStatus.SCHEDULED) {
                        it.copy(status = RecordingStatus.RECORDING)
                    }
                    if (updated != null) {
                        startCapture(updated)
                    }
                }
            }

        snapshot
            .filter { it.status == RecordingStatus.RECORDING && it.scheduledEndMs in 1 until now }
            .forEach { stopCandidate ->
                removeActiveJob(stopCandidate.id)?.cancel()
                val fileLength = stopCandidate.outputPath?.let { path -> File(path).takeIf { it.exists() }?.length() } ?: 0L
                updateItemIf(stopCandidate.id, expectedStatus = RecordingStatus.RECORDING) {
                    it.copy(
                        status = if (fileLength > 0L) RecordingStatus.COMPLETED else RecordingStatus.CANCELLED,
                        scheduledEndMs = now
                    )
                }
            }
    }

    private suspend fun startCapture(item: RecordingItem) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outputFile = File(item.outputPath ?: return@launch)
            outputFile.parentFile?.mkdirs()
            runCatching {
                captureStreamToFile(item, outputFile)
            }.onSuccess {
                updateItemIf(item.id, expectedStatus = RecordingStatus.RECORDING) { current ->
                    current.copy(status = RecordingStatus.COMPLETED)
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    val fileLength = outputFile.takeIf { it.exists() }?.length() ?: 0L
                    updateItemIf(item.id, expectedStatus = RecordingStatus.RECORDING) { current ->
                        current.copy(status = if (fileLength > 0L) RecordingStatus.COMPLETED else RecordingStatus.CANCELLED)
                    }
                } else {
                    updateItemIf(item.id, expectedStatus = RecordingStatus.RECORDING) { current ->
                        current.copy(status = RecordingStatus.FAILED, failureReason = error.message)
                    }
                }
            }
            removeActiveJob(item.id)
            storageState.value = readStorageState()
        }
        val replaced = stateMutex.withLock { activeJobs.putIfAbsent(item.id, job) }
        if (replaced != null) {
            job.cancel()
            return
        }
        job.start()
    }

    private suspend fun captureStreamToFile(item: RecordingItem, outputFile: File) {
        var attempt = 0
        var retryDelayMs = 1_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                streamResponseBody(item.streamUrl, outputFile)
                return
            } catch (error: Throwable) {
                if (error is CancellationException || !isRetryableRecordingError(error) || attempt >= MAX_RECORDING_RETRIES) {
                    throw error
                }
                attempt++
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(8_000L)
            }
        }
    }

    private fun buildOutputFile(request: RecordingRequest): File {
        val safeName = request.channelName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(recordingsDir, "${safeName}_${request.scheduledStartMs}.ts")
    }

    private fun readStorageState(): RecordingStorageState {
        recordingsDir.mkdirs()
        val stat = StatFs(recordingsDir.absolutePath)
        return RecordingStorageState(
            outputDirectory = recordingsDir.absolutePath,
            availableBytes = stat.availableBytes,
            isWritable = recordingsDir.canWrite()
        )
    }

    private fun persistStateLocked() {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(gson.toJson(itemsState.value))
    }

    private fun loadState(): List<RecordingItem> {
        if (!stateFile.exists()) return emptyList()
        return runCatching {
            val listType = object : TypeToken<List<RecordingItem>>() {}.type
            gson.fromJson<List<RecordingItem>>(FileInputStream(stateFile).bufferedReader(), listType).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun isAdaptiveStream(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd")
    }

    private suspend fun appendItem(item: RecordingItem) {
        stateMutex.withLock {
            itemsState.value = itemsState.value + item
            persistStateLocked()
        }
    }

    private suspend fun getItem(recordingId: String): RecordingItem? =
        stateMutex.withLock { itemsState.value.firstOrNull { it.id == recordingId } }

    private suspend fun updateItem(
        recordingId: String,
        transform: (RecordingItem) -> RecordingItem
    ): RecordingItem? = stateMutex.withLock {
        var updated: RecordingItem? = null
        itemsState.value = itemsState.value.map { item ->
            if (item.id == recordingId) {
                transform(item).also { updated = it }
            } else {
                item
            }
        }
        if (updated != null) {
            persistStateLocked()
        }
        updated
    }

    private suspend fun updateItemIf(
        recordingId: String,
        expectedStatus: RecordingStatus,
        transform: (RecordingItem) -> RecordingItem
    ): RecordingItem? = stateMutex.withLock {
        var updated: RecordingItem? = null
        itemsState.value = itemsState.value.map { item ->
            if (item.id == recordingId && item.status == expectedStatus) {
                transform(item).also { updated = it }
            } else {
                item
            }
        }
        if (updated != null) {
            persistStateLocked()
        }
        updated
    }

    private suspend fun removeActiveJob(recordingId: String): Job? =
        stateMutex.withLock { activeJobs.remove(recordingId) }

    private suspend fun streamResponseBody(url: String, outputFile: File) {
        val request = Request.Builder().url(url).get().build()
        val call = okHttpClient.newCall(request)
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                ensureSuccessfulRecordingResponse(response)
                val body = response.body ?: throw IOException("Recording stream returned an empty body")
                body.byteStream().use { input ->
                    FileOutputStream(outputFile, true).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = input.read(buffer)
                            if (bytes <= 0) {
                                break
                            }
                            output.write(buffer, 0, bytes)
                        }
                    }
                }
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun ensureSuccessfulRecordingResponse(response: Response) {
        if (response.isSuccessful) {
            return
        }
        if (response.code in 500..599 || response.code == 429) {
            throw IOException("Transient HTTP ${response.code}")
        }
        throw IllegalStateException("Recording stream failed with HTTP ${response.code}")
    }

    private fun isRetryableRecordingError(error: Throwable): Boolean {
        if (error is IOException) {
            return true
        }
        val message = error.message.orEmpty().lowercase()
        return message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("connection reset") ||
            message.contains("connect") ||
            message.contains("network")
    }

    private companion object {
        const val MAX_RECORDING_RETRIES = 3
    }
}
