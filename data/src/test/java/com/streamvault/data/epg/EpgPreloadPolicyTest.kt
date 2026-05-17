package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [EpgPreloadPolicyImpl] timing and cap.
 *
 * The policy exposes a `now` parameter on its public methods specifically so tests can drive
 * deterministic timelines without `Thread.sleep` or a mocked clock.
 */
class EpgPreloadPolicyTest {

    @Test
    fun `gate is open before any switch is notified`() {
        val policy = EpgPreloadPolicyImpl()

        // No notifyChannelSwitch yet. lastSwitchTs is initialised to a very negative value so
        // any current-time `now` is well past the gate window.
        assertThat(policy.isGateOpen(now = 0L)).isTrue()
        assertThat(policy.isGateOpen(now = 1_000_000L)).isTrue()
    }

    @Test
    fun `gate closes for 1500 ms after a channel switch`() {
        val policy = EpgPreloadPolicyImpl()

        policy.notifyChannelSwitch(now = 0L)

        assertThat(policy.isGateOpen(now = 0L)).isFalse()
        assertThat(policy.isGateOpen(now = 500L)).isFalse()
        assertThat(policy.isGateOpen(now = 1_499L)).isFalse()
        // Edge: gate opens exactly at lastSwitchTs + GATE_MS.
        assertThat(policy.isGateOpen(now = 1_500L)).isTrue()
        assertThat(policy.isGateOpen(now = 2_000L)).isTrue()
    }

    @Test
    fun `each notifyChannelSwitch resets the gate window`() {
        val policy = EpgPreloadPolicyImpl()

        policy.notifyChannelSwitch(now = 0L)
        // Gate has opened by t = 2_000.
        assertThat(policy.isGateOpen(now = 2_000L)).isTrue()

        // User switches again — gate must close for another 1500 ms.
        policy.notifyChannelSwitch(now = 2_000L)
        assertThat(policy.isGateOpen(now = 2_000L)).isFalse()
        assertThat(policy.isGateOpen(now = 3_499L)).isFalse()
        assertThat(policy.isGateOpen(now = 3_500L)).isTrue()
    }

    @Test
    fun `maxNeighbours equals the SCOPE D3 constant`() {
        val policy = EpgPreloadPolicyImpl()
        assertThat(policy.maxNeighbours).isEqualTo(4)
        assertThat(EpgPreloadPolicy.MAX_NEIGHBOURS).isEqualTo(4)
        assertThat(EpgPreloadPolicy.GATE_MS).isEqualTo(1_500L)
    }

    @Test
    fun `notifyChannelSwitch with the same now keeps the gate closed at that instant`() {
        // Realistic case: changeChannel() calls notifyChannelSwitch() and then preload code
        // immediately checks isGateOpen() on the same clock reading. The check must see the
        // gate as closed (no race window).
        val policy = EpgPreloadPolicyImpl()

        policy.notifyChannelSwitch(now = 10_000L)
        assertThat(policy.isGateOpen(now = 10_000L)).isFalse()
    }
}
