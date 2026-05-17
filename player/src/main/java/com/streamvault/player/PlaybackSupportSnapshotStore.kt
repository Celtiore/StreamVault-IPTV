package com.streamvault.player

import android.content.Context
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stocke un snapshot diagnostic du support de lecture sur disque.
 *
 * Single-writer by design — [Media3PlayerEngine.startEngineCollectors] is the unique caller
 * and runs sequentially in its CoroutineScope. No Mutex needed.
 */
@Singleton
class PlaybackSupportSnapshotStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val reportFile: File = File(context.filesDir, "diagnostics/crash/latest-playback-support.txt").also {
        it.parentFile?.mkdirs()
    }

    suspend fun write(report: String) {
        withContext(Dispatchers.IO) {
            check(Looper.getMainLooper().thread !== Thread.currentThread()) {
                "PlaybackSupportSnapshotStore.write must run off main thread"
            }
            runCatching {
                reportFile.writeText(report, Charsets.UTF_8)
            }.onFailure { error ->
                Log.w(TAG, "Failed to persist playback support snapshot", error)
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackSupportSnapshotStore"
    }
}
