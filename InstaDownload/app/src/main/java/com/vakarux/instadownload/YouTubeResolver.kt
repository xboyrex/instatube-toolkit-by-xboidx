package com.vakarux.instadownload

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

data class YouTubeMetadata(
    val videoId: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val canonicalUrl: String
)

/** Public metadata preview using YouTube's oEmbed endpoint; this never resolves protected media streams. */
object YouTubeResolver {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isYouTubeUrl(value: String): Boolean = extractVideoId(value) != null || isYouTubeHost(value)

    fun extractVideoId(value: String): String? {
        return runCatching {
            val uri = Uri.parse(value.trim())
            if (uri.scheme !in setOf("http", "https")) return@runCatching null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
            if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com")) return@runCatching null
            val id = when {
                host.endsWith("youtu.be") -> uri.pathSegments.firstOrNull()
                uri.path.equals("/watch", ignoreCase = true) -> uri.getQueryParameter("v")
                uri.pathSegments.firstOrNull()?.lowercase(Locale.ROOT) in setOf("shorts", "embed", "live", "v") -> uri.pathSegments.getOrNull(1)
                else -> null
            }
            id?.takeIf { videoIdPattern.matches(it) }
        }.getOrNull()
    }

    fun fetchMetadata(value: String): YouTubeMetadata {
        val id = extractVideoId(value) ?: throw IllegalArgumentException("Paste a single YouTube video, Shorts or youtu.be link. Playlist/channel links aren't supported for metadata preview yet.")
        val canonical = "https://www.youtube.com/watch?v=$id"
        val encoded = URLEncoder.encode(canonical, StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://www.youtube.com/oembed?url=$encoded&format=json")
            .header("User-Agent", "InstaTubeToolkit/1.2 (Android; public metadata preview)")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(when (response.code) {
                    404, 401, 403 -> "YouTube did not make metadata available for this video. It may be private, deleted or not embeddable."
                    429 -> "YouTube is temporarily rate-limiting metadata requests. Try again later."
                    else -> "YouTube metadata request failed (HTTP ${response.code})."
                })
            }
            val json = JSONObject(body)
            val title = json.optString("title").trim().ifBlank { throw IllegalStateException("YouTube returned no title for this video.") }
            val author = json.optString("author_name").trim().ifBlank { "YouTube creator" }
            val thumbnail = json.optString("thumbnail_url").trim()
            if (thumbnail.isBlank()) throw IllegalStateException("YouTube returned no thumbnail for this video.")
            return YouTubeMetadata(id, title, author, thumbnail, canonical)
        }
    }

    private fun isYouTubeHost(value: String): Boolean = runCatching {
        val uri = Uri.parse(value.trim())
        uri.scheme in setOf("http", "https") && uri.host?.lowercase(Locale.ROOT) in setOf(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com"
        )
    }.getOrDefault(false)
}
