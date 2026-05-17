package com.streamvault.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.epg.EpgPreloadPolicy
import com.streamvault.data.epg.EpgPreloadPolicyImpl
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProgramReminderDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.RecordingRunDao
import com.streamvault.data.manager.recording.RecordingAlarmScheduler
import com.streamvault.data.manager.reminder.ProgramReminderAlarmScheduler
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.LiveStreamProgramRequest
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Integration test for M7 P2 — verifies that ProviderRepositoryImpl.getProgramsForLiveStreams
 * respects the EpgPreloadPolicy gate (skip whole batch) and cap (max 4 entries).
 *
 * We compose the real ProviderRepositoryImpl with a real EpgPreloadPolicyImpl and a mocked
 * ProviderDao that returns null. This means:
 * - When the gate is closed: the method must short-circuit BEFORE the DAO lookup and return a
 *   "gated" error for every input request.
 * - When the gate is open: the method applies the .take(4) cap, then hits the DAO (which
 *   returns null in this fake), so the resulting map has the same size as the capped input.
 *
 * No HTTP, no Xtream provider context, no extra coroutine machinery beyond runTest.
 */
class ProviderRepositoryImplEpgGateTest {

    private val providerDao: ProviderDao = mock<ProviderDao>().apply {
        // No provider known → forces the method to take the "Provider $providerId not found"
        // branch, which is enough to count returned entries without exercising HTTP.
    }
    private val channelDao: ChannelDao = mock()
    private val programDao: ProgramDao = mock()
    private val recordingRunDao: RecordingRunDao = mock()
    private val programReminderDao: ProgramReminderDao = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val credentialCrypto: CredentialCrypto = mock()
    private val preferencesRepository: PreferencesRepository = mock<PreferencesRepository>().apply {
        whenever(xtreamBase64TextCompatibility).thenReturn(flowOf(false))
    }
    private val syncManager: SyncManager = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val recordingAlarmScheduler: RecordingAlarmScheduler = mock()
    private val programReminderAlarmScheduler: ProgramReminderAlarmScheduler = mock()
    private val appContext: Context = mock<Context>().apply {
        whenever(applicationInfo).thenReturn(ApplicationInfo().apply { flags = 0 })
    }
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun createRepo(policy: EpgPreloadPolicy) = ProviderRepositoryImpl(
        providerDao = providerDao,
        channelDao = channelDao,
        programDao = programDao,
        recordingRunDao = recordingRunDao,
        programReminderDao = programReminderDao,
        stalkerApiService = stalkerApiService,
        xtreamApiService = xtreamApiService,
        credentialCrypto = credentialCrypto,
        preferencesRepository = preferencesRepository,
        syncManager = syncManager,
        syncMetadataRepository = syncMetadataRepository,
        transactionRunner = transactionRunner,
        recordingAlarmScheduler = recordingAlarmScheduler,
        programReminderAlarmScheduler = programReminderAlarmScheduler,
        epgPreloadPolicy = policy,
        appContext = appContext
    )

    private fun tenRequests(): List<LiveStreamProgramRequest> =
        (1L..10L).map { LiveStreamProgramRequest(streamId = it, epgChannelId = "ch-$it") }

    @Test
    fun `gate closed within 1500 ms — entire batch is short-circuited with errors`() = runTest {
        val policy = EpgPreloadPolicyImpl()
        val repo = createRepo(policy)

        // Switch happened "now" — the gate is closed for the next 1500 ms.
        policy.notifyChannelSwitch()

        val results = repo.getProgramsForLiveStreams(
            providerId = 1L,
            requests = tenRequests(),
            limit = 6
        )

        // All 10 inputs come back (caller can inspect each), every entry is the gated error,
        // and crucially providerDao.getById was never reached — verified implicitly by the fact
        // that the error message is the gate one, not "Provider 1 not found".
        assertThat(results).hasSize(10)
        results.values.forEach { result ->
            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).message)
                .isEqualTo("EPG neighbour preload gated post channel switch")
        }
    }

    @Test
    fun `gate open — cap reduces fan-out to 4 entries`() = runTest {
        // No notifyChannelSwitch → gate is open from t = 0.
        val policy = EpgPreloadPolicyImpl()
        val repo = createRepo(policy)

        val results = repo.getProgramsForLiveStreams(
            providerId = 1L,
            requests = tenRequests(),
            limit = 6
        )

        // 10 input requests → capped to 4 before fan-out. ProviderDao returns null, so the cap
        // surfaces as exactly 4 "Provider not found" entries (the real cap point sits before
        // the DAO call).
        assertThat(results).hasSize(4)
        results.values.forEach { result ->
            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).message).isEqualTo("Provider 1 not found")
        }
    }
}
