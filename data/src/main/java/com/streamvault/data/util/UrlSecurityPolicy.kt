package com.streamvault.data.util

import java.net.URI
import java.util.Locale

object UrlSecurityPolicy {
    private val secureRemoteSchemes = setOf("https")
    private val localSchemes = setOf("file", "content")

    fun isSecureRemoteUrl(url: String): Boolean = hasAllowedScheme(url, secureRemoteSchemes)

    fun isAllowedImportedUrl(url: String): Boolean =
        hasAllowedScheme(url, secureRemoteSchemes + localSchemes)

    fun validateXtreamServerUrl(url: String): String? {
        return if (isSecureRemoteUrl(url)) {
            null
        } else {
            "Only HTTPS Xtream server URLs are supported."
        }
    }

    fun validatePlaylistSourceUrl(url: String): String? {
        return if (isAllowedImportedUrl(url)) {
            null
        } else {
            "Playlist sources must use HTTPS or point to a local file."
        }
    }

    fun validateOptionalEpgUrl(url: String): String? {
        return if (url.isBlank() || isSecureRemoteUrl(url)) {
            null
        } else {
            "EPG URLs must use HTTPS."
        }
    }

    fun sanitizeImportedAssetUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() && isAllowedImportedUrl(it) }
    }

    private fun hasAllowedScheme(url: String, allowedSchemes: Set<String>): Boolean {
        val scheme = parseScheme(url) ?: return false
        return scheme in allowedSchemes
    }

    private fun parseScheme(url: String): String? {
        return runCatching { URI(url.trim()).scheme }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
    }
}
