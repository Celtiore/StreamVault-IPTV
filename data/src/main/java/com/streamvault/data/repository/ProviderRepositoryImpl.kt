package com.streamvault.data.repository

import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.mapper.*
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.*
import com.streamvault.domain.provider.IptvProvider
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val programDao: ProgramDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val xtreamApiService: XtreamApiService,
    private val syncManager: SyncManager
) : ProviderRepository {

    override fun getProviders(): Flow<List<Provider>> =
        providerDao.getAll().map { entities -> entities.map { it.toPublicDomain() } }

    override fun getActiveProvider(): Flow<Provider?> =
        providerDao.getActive().map { it?.toPublicDomain() }

    override suspend fun getProvider(id: Long): Provider? =
        providerDao.getById(id)?.toPublicDomain()

    override suspend fun addProvider(provider: Provider): Result<Long> = try {
        val id = providerDao.insert(provider.toSecureEntity())
        Result.success(id)
    } catch (e: Exception) {
        Result.error("Failed to add provider: ${e.message}", e)
    }

    override suspend fun updateProvider(provider: Provider): Result<Unit> = try {
        providerDao.update(provider.toSecureEntity())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update provider: ${e.message}", e)
    }

    override suspend fun deleteProvider(id: Long): Result<Unit> = try {
        programDao.deleteByProvider(id)
        playbackHistoryDao.deleteByProvider(id)
        syncMetadataDao.delete(id)
        channelDao.deleteByProvider(id)
        movieDao.deleteByProvider(id)
        seriesDao.deleteByProvider(id)
        categoryDao.deleteByProviderAndType(id, "LIVE")
        categoryDao.deleteByProviderAndType(id, "MOVIE")
        categoryDao.deleteByProviderAndType(id, "SERIES")
        providerDao.delete(id)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to delete provider: ${e.message}", e)
    }

    override suspend fun setActiveProvider(id: Long): Result<Unit> = try {
        providerDao.deactivateAll()
        providerDao.activate(id)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to set active provider: ${e.message}", e)
    }

    override suspend fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        UrlSecurityPolicy.validateXtreamServerUrl(serverUrl)?.let { message ->
            return Result.error(message)
        }
        onProgress?.invoke("Authenticating...")
        val existingProvider = if (id != null) {
            providerDao.getById(id)
        } else {
            providerDao.getByUrlAndUser(serverUrl, username)
        }
        val effectivePassword = password.takeIf { it.isNotBlank() }
            ?: existingProvider?.password?.let(CredentialCrypto::decryptIfNeeded)
            ?: ""
        val provider = createXtreamProvider(0, serverUrl, username, effectivePassword)
        return when (val authResult = provider.authenticate()) {
            is Result.Success -> {

                val providerData = if (existingProvider != null) {
                    onProgress?.invoke("Updating existing provider...")
                    val updated = existingProvider.copy(
                        name = name.ifBlank { existingProvider.name },
                        serverUrl = serverUrl,
                        username = username,
                        password = effectivePassword,
                        isActive = true,
                        lastSyncedAt = 0
                    )
                    providerDao.update(
                        updated.copy(password = CredentialCrypto.encryptIfNeeded(updated.password))
                    )
                    updated.toPublicDomain()
                } else {
                    val newData = authResult.data.copy(name = name.ifBlank { authResult.data.name })
                    val newId = providerDao.insert(newData.toSecureEntity())
                    newData.copy(id = newId).copy(password = "")
                }

                providerDao.deactivateAll()
                providerDao.activate(providerData.id)

                when (val syncResult = syncManager.sync(providerData.id, force = false, onProgress = onProgress)) {
                    is Result.Success -> {
                        val finalStatus = if (syncManager.syncState.value is SyncState.Partial) {
                            ProviderStatus.PARTIAL
                        } else {
                            ProviderStatus.ACTIVE
                        }
                        updateProviderSyncStatus(providerData.id, finalStatus, System.currentTimeMillis())
                        Result.success(providerData.copy(status = finalStatus))
                    }
                    is Result.Error -> {
                        updateProviderSyncStatus(providerData.id, ProviderStatus.ERROR)
                        if (existingProvider == null) {
                            // Rollback: if this was a new provider and initial sync failed, don't leave it in the database
                            providerDao.delete(providerData.id)
                        }
                        Result.error(
                            "Provider authenticated, but initial sync failed: ${syncResult.message}",
                            syncResult.exception
                        )
                    }
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun validateM3u(
        url: String,
        name: String,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> = try {
        UrlSecurityPolicy.validatePlaylistSourceUrl(url)?.let { message ->
            return Result.error(message)
        }
        onProgress?.invoke("Validating playlist URL...")
        val providerName = name.ifBlank {
            url.substringAfterLast("/").substringBefore("?").ifBlank { "M3U Playlist" }
        }

        val existingProvider = if (id != null) {
            providerDao.getById(id)
        } else {
            providerDao.getByUrlAndUser(url, "")
        }

        val providerData = if (existingProvider != null) {
            val updated = existingProvider.copy(
                name = if (name.isNotBlank()) name else existingProvider.name,
                serverUrl = url,
                m3uUrl = url,
                isActive = true,
                lastSyncedAt = 0
            )
            providerDao.update(updated)
            updated.toPublicDomain()
        } else {
            val provider = Provider(
                name = providerName,
                type = ProviderType.M3U,
                serverUrl = url,
                m3uUrl = url,
                status = ProviderStatus.ACTIVE
            )
            val newId = providerDao.insert(provider.toSecureEntity())
            provider.copy(id = newId).copy(password = "")
        }

        providerDao.deactivateAll()
        providerDao.activate(providerData.id)

        when (val syncResult = syncManager.sync(providerData.id, force = false, onProgress = onProgress)) {
            is Result.Success -> {
                val finalStatus = if (syncManager.syncState.value is SyncState.Partial) {
                    ProviderStatus.PARTIAL
                } else {
                    ProviderStatus.ACTIVE
                }
                updateProviderSyncStatus(providerData.id, finalStatus, System.currentTimeMillis())
                Result.success(providerData.copy(status = finalStatus))
            }
            is Result.Error -> {
                updateProviderSyncStatus(providerData.id, ProviderStatus.ERROR)
                if (existingProvider == null) {
                    // Rollback: if this was a new provider and initial sync failed, don't leave it in the database
                    providerDao.delete(providerData.id)
                }
                Result.error(
                    "Playlist saved, but initial sync failed: ${syncResult.message}",
                    syncResult.exception
                )
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    } catch (e: Exception) {
        Result.error("Failed to add M3U provider: ${e.message}", e)
    }

    /**
     * Delegates to [SyncManager] — the single source of truth for the full sync pipeline.
     */
    override suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> {
        return when (val syncResult = syncManager.sync(providerId, force = force, onProgress = onProgress)) {
            is Result.Success -> {
                val finalStatus = if (syncManager.syncState.value is SyncState.Partial) {
                    ProviderStatus.PARTIAL
                } else {
                    ProviderStatus.ACTIVE
                }
                updateProviderSyncStatus(providerId, finalStatus, System.currentTimeMillis())
                syncResult
            }
            is Result.Error -> {
                updateProviderSyncStatus(providerId, ProviderStatus.ERROR)
                syncResult
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String? {
        val providerEntity = providerDao.getById(providerId) ?: return null
        val provider = providerEntity.toPublicDomain()
        val providerPassword = CredentialCrypto.decryptIfNeeded(providerEntity.password)
        val channel = channelDao.getById(streamId)
        val resolvedStreamId = channel?.streamId?.takeIf { it > 0 } ?: streamId
        return if (provider.type == ProviderType.XTREAM_CODES) {
            createXtreamProvider(providerId, provider.serverUrl, provider.username, providerPassword)
                .buildCatchUpUrl(resolvedStreamId, start, end)
        } else {
            // M3U catch-up
            val source = channel?.catchUpSource ?: return null

            // Substitute common provider placeholder variants.
            source.replace("{start}", start.toString())
                .replace("{end}", end.toString())
                .replace("{duration}", (end - start).toString())
                .replace("{utc}", start.toString())
                .replace("{utcend}", end.toString())
                .replace("{lutc}", end.toString())
                .replace("{timestamp}", start.toString())
        }
    }

    fun createXtreamProvider(
        providerId: Long,
        serverUrl: String,
        username: String,
        password: String
    ): IptvProvider = XtreamProvider(
        providerId = providerId,
        api = xtreamApiService,
        serverUrl = serverUrl,
        username = username,
        password = password
    )

    private fun ProviderEntity.toPublicDomain(): Provider {
        return toDomain().copy(password = "")
    }

    private fun Provider.toSecureEntity(): ProviderEntity {
        val encryptedPassword = CredentialCrypto.encryptIfNeeded(password)
        return copy(password = encryptedPassword).toEntity()
    }

    private suspend fun updateProviderSyncStatus(
        providerId: Long,
        status: ProviderStatus,
        lastSyncedAt: Long? = null
    ) {
        val current = providerDao.getById(providerId) ?: return
        val updated = current.copy(
            status = status.name,
            lastSyncedAt = lastSyncedAt ?: current.lastSyncedAt
        )
        providerDao.update(updated)
    }
}
