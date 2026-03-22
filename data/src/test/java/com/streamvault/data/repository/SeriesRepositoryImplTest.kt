package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SeriesRepositoryImplTest {

    private val seriesDao: SeriesDao = mock()
    private val episodeDao: EpisodeDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val playbackHistoryDao: PlaybackHistoryDao = mock()
    private val providerDao: ProviderDao = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()

    @Test
    fun `getSeriesByCategory lazily hydrates xtream category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(xtreamApiService.getSeriesCategories(any())).thenReturn(
            listOf(XtreamCategory(categoryId = "77", categoryName = "Drama"))
        )
        whenever(xtreamApiService.getSeriesList(any())).thenReturn(
            listOf(
                XtreamSeriesItem(
                    seriesId = 301L,
                    name = "Series",
                    categoryId = "77",
                    cover = null
                )
            )
        )
        whenever(episodeDao.deleteOrphans()).thenReturn(0)

        val repository = createRepository()

        val result = repository.getSeriesByCategory(7L, 77L).first()

        assertThat(result).isEmpty()
        verify(seriesDao).replaceCategory(eq(7L), eq(77L), any())
        verify(episodeDao).deleteOrphans()
    }

    private fun createRepository() = SeriesRepositoryImpl(
        seriesDao = seriesDao,
        episodeDao = episodeDao,
        categoryDao = categoryDao,
        favoriteDao = favoriteDao,
        playbackHistoryDao = playbackHistoryDao,
        providerDao = providerDao,
        xtreamApiService = xtreamApiService,
        preferencesRepository = preferencesRepository,
        xtreamStreamUrlResolver = xtreamStreamUrlResolver
    )
}
