package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    fun getChannels(providerId: Long): Flow<List<Channel>>
    fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>>
    fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>>
    fun getCategories(providerId: Long): Flow<List<Category>>
    fun searchChannels(providerId: Long, query: String): Flow<List<Channel>>
    suspend fun getChannel(channelId: Long): Channel?
    suspend fun getStreamUrl(channel: Channel): Result<String>
    suspend fun refreshChannels(providerId: Long): Result<Unit>
    fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>>
    suspend fun incrementChannelErrorCount(channelId: Long)
    suspend fun resetChannelErrorCount(channelId: Long)

    companion object {
        const val ALL_CHANNELS_ID = -1_000_000L
    }
}
