package com.streamvault.data.epg

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared policy that throttles neighbour EPG preload bursts triggered by a fast channel switch.
 *
 * Two mechanisms (M7 Phase 2):
 * - Gate: blocks neighbour EPG preloads for [GATE_MS] after a channel switch is announced via
 *   [notifyChannelSwitch]. The user-facing "current channel" EPG fetch is intentionally not gated;
 *   only neighbour/fan-out batches must consult [isGateOpen] before issuing requests.
 * - Cap: bounds neighbour batches to [maxNeighbours] entries. This caps the worst-case
 *   per-provider fan-out (callers running `.take(MAX_XTREAM_GUIDE_FALLBACK_CHANNELS)` etc.)
 *   so multi-provider lists don't multiply the burst.
 *
 * Default values come from the M7 SCOPE decisions D2 (1500 ms gate) and D3 (cap = 4, aligned
 * with the existing `XTREAM_GUIDE_BATCH_CONCURRENCY` in ProviderRepositoryImpl).
 *
 * Threading: writes (via [notifyChannelSwitch]) happen on the UI/main thread on channel switch.
 * Reads ([isGateOpen]) happen from coroutine-backed IO dispatchers when batches are about to
 * fire. `@Volatile` is sufficient — a stale read of `lastSwitchTs` only delays/permits a batch
 * by a few ms, which is well below the 1500 ms gate window.
 */
interface EpgPreloadPolicy {
    /** Marks that the user just switched channels. Closes the gate for [GATE_MS]. */
    fun notifyChannelSwitch(now: Long = SystemClock.elapsedRealtime())

    /** Returns `true` once [GATE_MS] has elapsed since the last [notifyChannelSwitch]. */
    fun isGateOpen(now: Long = SystemClock.elapsedRealtime()): Boolean

    /** Maximum number of neighbour channels that may share a single preload batch. */
    val maxNeighbours: Int

    companion object {
        /** Gate duration in milliseconds — M7 SCOPE D2. */
        const val GATE_MS: Long = 1_500L

        /** Neighbour cap per batch — M7 SCOPE D3. */
        const val MAX_NEIGHBOURS: Int = 4
    }
}

@Singleton
class EpgPreloadPolicyImpl @Inject constructor() : EpgPreloadPolicy {

    @Volatile
    private var lastSwitchTs: Long = Long.MIN_VALUE / 2

    override val maxNeighbours: Int = EpgPreloadPolicy.MAX_NEIGHBOURS

    override fun notifyChannelSwitch(now: Long) {
        lastSwitchTs = now
    }

    override fun isGateOpen(now: Long): Boolean {
        return (now - lastSwitchTs) >= EpgPreloadPolicy.GATE_MS
    }
}
