package com.streamvault.data.remote.xtream

import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import com.streamvault.data.remote.NetworkTimeoutConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit.SECONDS

class OkHttpXtreamApiService(
    private val client: OkHttpClient,
    private val json: Json
) : XtreamApiService {
    private enum class RequestProfile {
        STANDARD,
        HEAVY_CATALOG
    }

    private data class EndpointDescriptor(
        val action: String?,
        val host: String?,
        val path: String?,
        val hint: String
    )

    private val heavyCatalogClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_WRITE_TIMEOUT_SECONDS, SECONDS)
            .callTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_CALL_TIMEOUT_SECONDS, SECONDS)
            .build()
    }

    override suspend fun authenticate(endpoint: String): XtreamAuthResponse = get(endpoint)

    override suspend fun getLiveCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getLiveStreams(endpoint: String): List<XtreamStream> = get(endpoint)

    override suspend fun getVodCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getVodStreams(endpoint: String): List<XtreamStream> = get(endpoint, RequestProfile.HEAVY_CATALOG)

    override suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse = get(endpoint)

    override suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem> = get(endpoint, RequestProfile.HEAVY_CATALOG)

    override suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse = get(endpoint)

    override suspend fun getShortEpg(endpoint: String): XtreamEpgResponse = get(endpoint)

    override suspend fun getFullEpg(endpoint: String): XtreamEpgResponse = get(endpoint)

    private suspend inline fun <reified T> get(
        endpoint: String,
        profile: RequestProfile = RequestProfile.STANDARD
    ): T = withContext(Dispatchers.IO) {
        val descriptor = describeEndpoint(endpoint)
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
        try {
            val call = clientFor(profile).newCall(request)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}"
                    when (response.code) {
                        401, 403 -> throw XtreamAuthenticationException(response.code, message)
                        in 500..599, 429 -> throw XtreamNetworkException(message)
                        else -> throw XtreamRequestException(response.code, message)
                    }
                }
                val body = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw XtreamParsingException("Empty response body from ${descriptor.hint}")
                inspectResponseShape(
                    body = body,
                    contentType = response.header("Content-Type"),
                    descriptor = descriptor
                )?.let { throw it }
                try {
                    json.decodeFromString<T>(body)
                } catch (e: SerializationException) {
                    val preview = sanitizedPreview(body)
                    throw XtreamParsingException(
                        "Malformed JSON from ${descriptor.hint}${if (preview != null) " (preview=$preview)" else ""}",
                        e
                    )
                }
            }
        } catch (e: XtreamApiException) {
            throw e
        } catch (e: IOException) {
            throw XtreamNetworkException(XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed"), e)
        }
    }

    private fun clientFor(profile: RequestProfile): OkHttpClient = when (profile) {
        RequestProfile.STANDARD -> client
        RequestProfile.HEAVY_CATALOG -> heavyCatalogClient
    }

    private fun describeEndpoint(endpoint: String): EndpointDescriptor {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        val action = uri?.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("action=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
        val host = uri?.host
        val path = uri?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val hint = buildString {
            append(host ?: "<unknown-host>")
            if (!path.isNullOrBlank()) append("/").append(path)
            if (!action.isNullOrBlank()) append("?action=").append(action)
        }
        return EndpointDescriptor(
            action = action,
            host = host,
            path = path,
            hint = XtreamUrlFactory.sanitizeLogMessage(hint)
        )
    }

    private fun inspectResponseShape(
        body: String,
        contentType: String?,
        descriptor: EndpointDescriptor
    ): XtreamParsingException? {
        val trimmed = body.trimStart()
        val normalizedContentType = contentType?.lowercase().orEmpty()
        val preview = sanitizedPreview(body)
        val previewSuffix = if (preview != null) " (preview=$preview)" else ""
        return when {
            trimmed.isBlank() ->
                XtreamParsingException("Blank response body from ${descriptor.hint}")
            normalizedContentType.contains("html") ||
                trimmed.startsWith("<!doctype html", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true) ||
                trimmed.contains("<body", ignoreCase = true) ||
                trimmed.contains("</html>", ignoreCase = true) ->
                XtreamParsingException("HTML error page returned from ${descriptor.hint}$previewSuffix")
            trimmed.startsWith("<") ->
                XtreamParsingException("Markup/non-JSON response returned from ${descriptor.hint}$previewSuffix")
            !trimmed.startsWith("{") && !trimmed.startsWith("[") ->
                XtreamParsingException("Non-JSON text response returned from ${descriptor.hint}$previewSuffix")
            else -> null
        }
    }

    private fun sanitizedPreview(body: String): String? {
        return body
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.take(140)
            ?.let(XtreamUrlFactory::sanitizeLogMessage)
    }
}
