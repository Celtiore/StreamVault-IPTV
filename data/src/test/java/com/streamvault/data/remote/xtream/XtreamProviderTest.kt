package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class XtreamProviderTest {

    private val api: XtreamApiService = mock()

    @Test
    fun `adult category ids are cached across repeated live requests`() = runTest {
        whenever(api.getLiveCategories(any(), any(), any(), any())).thenReturn(
            listOf(
                XtreamCategory(categoryId = "100", categoryName = "Adult"),
                XtreamCategory(categoryId = "200", categoryName = "News")
            )
        )
        whenever(api.getLiveStreams(any(), any(), any(), any(), anyOrNull())).thenReturn(
            listOf(
                XtreamStream(name = "Late Night", streamId = 1L, categoryId = "100"),
                XtreamStream(name = "Morning News", streamId = 2L, categoryId = "200")
            )
        )

        val provider = XtreamProvider(
            providerId = 1L,
            api = api,
            serverUrl = "https://provider.example.com",
            username = "demo",
            password = "secret"
        )

        val first = provider.getLiveStreams()
        val second = provider.getLiveStreams()

        assertThat(first.isSuccess).isTrue()
        assertThat(second.isSuccess).isTrue()
        verify(api, times(1)).getLiveCategories(any(), any(), any(), any())
        verify(api, times(2)).getLiveStreams(any(), any(), any(), any(), anyOrNull())
    }
}
