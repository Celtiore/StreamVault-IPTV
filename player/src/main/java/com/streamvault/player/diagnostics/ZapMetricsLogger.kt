package com.streamvault.player.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only instrumentation for the channel zap pipeline.
 *
 * Emits a single JSON line per event via [Log.i] with the [TAG] `"ZapMetrics"`, consumable by
 * `scripts/parse-zap-metrics.py`. All public methods are no-ops on release builds (when the
 * hosting application is not flagged [ApplicationInfo.FLAG_DEBUGGABLE]).
 *
 * ### Measured pipeline
 *
 * ```
 * intent (PlayerZapActions.changeChannel)
 *   → release   (MediaCodecVideoRenderer.onCodecReleased / AnalyticsListener.onVideoDecoderReleased)
 *   → codec_start (MediaCodecVideoRenderer.onReadyToInitializeCodec / onVideoDecoderInitialized)
 *   → surface_gen (AnalyticsListener.onSurfaceSizeChanged or equivalent)
 *   → first_frame (AnalyticsListener.onRenderedFirstFrame)
 * epg_request × N (ProviderRepositoryImpl.getProgramsForLiveStreams, one per async)
 * ```
 *
 * ### Flush non instrumenté
 *
 * `MediaCodec.flush()` n'est pas exposé via l'API publique de Media3 1.9.2. Le gap mesuré reste
 * `release → first_frame`, qui est le gap noir perçu côté utilisateur — c'est la métrique qui
 * compte pour la cible P50/P95 du M7.
 *
 * ### Event schema
 *
 * Tous les events partagent : `event` (string), `ts_ms` (monotone, [SystemClock.elapsedRealtime]),
 * `elapsed_since_intent_ms` (relatif au dernier `intent`, ou `0` si pas d'intent vu).
 *
 * - `intent` : `channelId: Long`
 * - `release`, `codec_start`, `first_frame` : pas de payload additionnel
 * - `surface_gen` : `gen: Int`
 * - `epg_request` : `streamId: Long`
 */
@Singleton
class ZapMetricsLogger @Inject constructor(
    @ApplicationContext context: Context
) {
    private val enabled: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Volatile
    private var lastIntentTsMs: Long = 0L

    fun markIntent(channelId: Long) {
        if (!enabled) return
        val ts = SystemClock.elapsedRealtime()
        lastIntentTsMs = ts
        emit("""{"event":"intent","channelId":$channelId,"ts_ms":$ts,"elapsed_since_intent_ms":0}""")
    }

    fun markRelease() {
        if (!enabled) return
        emitWithElapsed("release", extra = null)
    }

    fun markSurfaceGen(gen: Int) {
        if (!enabled) return
        emitWithElapsed("surface_gen", extra = "\"gen\":$gen")
    }

    fun markCodecStart() {
        if (!enabled) return
        emitWithElapsed("codec_start", extra = null)
    }

    fun markFirstFrame() {
        if (!enabled) return
        emitWithElapsed("first_frame", extra = null)
    }

    fun markEpgRequest(streamId: Long) {
        if (!enabled) return
        emitWithElapsed("epg_request", extra = "\"streamId\":$streamId")
    }

    private fun emitWithElapsed(event: String, extra: String?) {
        val ts = SystemClock.elapsedRealtime()
        val intent = lastIntentTsMs
        val elapsed = if (intent == 0L) 0L else ts - intent
        val payload = if (extra == null) {
            """{"event":"$event","ts_ms":$ts,"elapsed_since_intent_ms":$elapsed}"""
        } else {
            """{"event":"$event",$extra,"ts_ms":$ts,"elapsed_since_intent_ms":$elapsed}"""
        }
        emit(payload)
    }

    private fun emit(payload: String) {
        Log.i(TAG, payload)
    }

    companion object {
        /** Logcat tag. Must match the filter in `scripts/parse-zap-metrics.py`. */
        const val TAG: String = "ZapMetrics"
    }
}
