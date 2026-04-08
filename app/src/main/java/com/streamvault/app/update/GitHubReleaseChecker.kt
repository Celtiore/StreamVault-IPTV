package com.streamvault.app.update

import com.streamvault.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubReleaseInfo(
    val versionName: String,
    val versionCode: Int?,
    val releaseUrl: String,
    val downloadUrl: String?,
    val releaseNotes: String,
    val publishedAt: String?
)

@Singleton
class GitHubReleaseChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private companion object {
        private const val RELEASES_LATEST_URL = "https://api.github.com/repos/Davidona/StreamVault-IPTV/releases/latest"
        private val VERSION_CODE_REGEX = Regex("""code\s*`?(\d+)`?""", RegexOption.IGNORE_CASE)
    }

    suspend fun fetchLatestRelease(): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "StreamVault-Update-Checker")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.error("Update check failed: HTTP ${response.code}")
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.error("Update check failed: empty GitHub release response")
                }

                val json = JSONObject(body)
                val tagName = json.optString("tag_name").removePrefix("v").trim()
                if (tagName.isBlank()) {
                    return@withContext Result.error("Update check failed: latest release tag is missing")
                }

                val notes = json.optString("body").trim()
                val assets = json.optJSONArray("assets")
                val downloadUrl = findApkAssetUrl(assets)

                return@withContext Result.success(
                    GitHubReleaseInfo(
                        versionName = tagName,
                        versionCode = VERSION_CODE_REGEX.find(notes)?.groupValues?.getOrNull(1)?.toIntOrNull(),
                        releaseUrl = json.optString("html_url"),
                        downloadUrl = downloadUrl,
                        releaseNotes = notes,
                        publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (error: IOException) {
            Result.error("Update check failed: network error", error)
        } catch (error: Exception) {
            Result.error("Update check failed: ${error.message}", error)
        }
    }

    private fun findApkAssetUrl(assets: org.json.JSONArray?): String? {
        if (assets == null) return null
        var fallback: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            if (name.equals("StreamVault.apk", ignoreCase = true)) {
                return url
            }
            if (fallback == null && name.endsWith(".apk", ignoreCase = true)) {
                fallback = url
            }
        }
        return fallback
    }
}