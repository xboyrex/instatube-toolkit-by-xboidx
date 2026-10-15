package com.vakarux.instadownload

import android.net.Uri
import android.text.Html
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
    val canonicalUrl: String,
    val description: String = "",
    val keywords: List<String> = emptyList(),
    val durationSeconds: Long = 0L
)

private data class YouTubeWatchData(val playerResponse: JSONObject?, val html: String)

/** Public metadata preview only. It never resolves protected video/audio media streams. */
object YouTubeResolver {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val allowedHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com"
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isYouTubeUrl(value: String): Boolean = extractVideoId(value) != null || isYouTubeHost(value)

    fun extractVideoId(value: String): String? = runCatching {
        val uri = Uri.parse(value.trim())
        if (uri.scheme !in setOf("http", "https")) return@runCatching null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
        if (host !in allowedHosts) return@runCatching null
        val id = when {
            host.endsWith("youtu.be") -> uri.pathSegments.firstOrNull()
            uri.path.equals("/watch", ignoreCase = true) -> uri.getQueryParameter("v")
            uri.pathSegments.firstOrNull()?.lowercase(Locale.ROOT) in setOf("shorts", "embed", "live", "v") -> uri.pathSegments.getOrNull(1)
            else -> null
        }
        id?.takeIf { videoIdPattern.matches(it) }
    }.getOrNull()

    fun fetchMetadata(value: String): YouTubeMetadata {
        val id = extractVideoId(value) ?: throw IllegalArgumentException(
            "Paste a single YouTube video, Shorts or youtu.be link. Playlist/channel links are not supported for video metadata."
        )
        val canonical = "https://www.youtube.com/watch?v=$id"
        val encoded = URLEncoder.encode(canonical, StandardCharsets.UTF_8.name())
        val oembed = Request.Builder()
            .url("https://www.youtube.com/oembed?url=$encoded&format=json")
            .header("User-Agent", "InstaTubeToolkit/1.3 (Android; public metadata preview)")
            .get().build()
        val embed = client.newCall(oembed).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(when (response.code) {
                404, 401, 403 -> "YouTube did not make metadata available for this video. It may be private, deleted or not embeddable."
                429 -> "YouTube is temporarily rate-limiting metadata requests. Try again later."
                else -> "YouTube metadata request failed (HTTP ${response.code})."
            })
            JSONObject(body)
        }

        // The public oEmbed response does not include description or tags. Try the public watch page
        // for those fields; oEmbed remains the graceful fallback if the page is blocked or changes.
        val watch = runCatching { fetchWatchData(id) }.getOrNull()
        val details = watch?.playerResponse?.optJSONObject("videoDetails")
        val title = details?.optString("title").notBlankOrNull() ?: embed.optString("title").trim()
        if (title.isBlank()) throw IllegalStateException("YouTube returned no title for this video.")
        val author = details?.optString("author").notBlankOrNull()
            ?: embed.optString("author_name").trim().ifBlank { "YouTube creator" }
        val thumbnail = bestThumbnail(details?.optJSONObject("thumbnail"))
            ?: embed.optString("thumbnail_url").trim()
        if (thumbnail.isBlank()) throw IllegalStateException("YouTube returned no thumbnail for this video.")

        val html = watch?.html.orEmpty()
        val rawDescription = details?.optString("shortDescription").notBlankOrNull()
            ?: extractMetaContent(html, "og:description", "property")
            ?: extractMetaContent(html, "description", "name")
            ?: ""
        val description = decodeText(rawDescription)
        val playerKeywords = details?.optJSONArray("keywords")
        val keywords = (0 until (playerKeywords?.length() ?: 0))
            .mapNotNull { playerKeywords?.optString(it)?.trim()?.takeIf { word -> word.isNotBlank() } }
            .distinct()
            .ifEmpty {
                extractMetaContent(html, "keywords", "name")?.split(",")
                    ?.map { decodeText(it).trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()
            }
        val duration = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L

        return YouTubeMetadata(
            videoId = id,
            title = decodeText(title),
            author = decodeText(author),
            thumbnailUrl = thumbnail,
            canonicalUrl = canonical,
            description = description,
            keywords = keywords,
            durationSeconds = duration
        )
    }

    private fun fetchWatchData(id: String): YouTubeWatchData {
        val request = Request.Builder()
            .url("https://www.youtube.com/watch?v=$id&hl=en")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build()
        return client.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            if (!response.isSuccessful || html.isBlank()) throw IllegalStateException("Public watch page unavailable (HTTP ${response.code}).")
            YouTubeWatchData(findPlayerResponse(html), html)
        }
    }

    private fun findPlayerResponse(html: String): JSONObject? {
        val marker = "ytInitialPlayerResponse"
        var from = 0
        while (from < html.length) {
            val markerIndex = html.indexOf(marker, from)
            if (markerIndex < 0) return null
            val start = html.indexOf('{', markerIndex + marker.length)
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until html.length) {
                val ch = html[index]
                if (inString) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') inString = false
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val result = runCatching { JSONObject(html.substring(start, index + 1)) }.getOrNull()
                                if (result?.has("videoDetails") == true) return result
                                break
                            }
                        }
                    }
                }
            }
            from = markerIndex + marker.length
        }
        return null
    }

    private fun bestThumbnail(thumbnails: JSONObject?): String? {
        val array = thumbnails?.optJSONArray("thumbnails") ?: return null
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("url") }
            .lastOrNull { it.isNotBlank() }
    }

    private fun extractMetaContent(html: String, key: String, keyAttribute: String): String? {
        if (html.isBlank()) return null
        val tagPattern = Regex("(?is)<meta\\b[^>]*>")
        for (match in tagPattern.findAll(html)) {
            val tag = match.value
            val keyValue = attribute(tag, keyAttribute) ?: continue
            if (!keyValue.equals(key, ignoreCase = true)) continue
            attribute(tag, "content")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun attribute(tag: String, name: String): String? {
        val pattern = Regex("""(?is)\b${Regex.escape(name)}\s*=\s*(['\"])(.*?)\1""")
        return pattern.find(tag)?.groupValues?.getOrNull(2)
    }

    private fun decodeText(value: String): String = Html.fromHtml(
        value.replace("\\u0026", "&").replace("\\/", "/"), Html.FROM_HTML_MODE_LEGACY
    ).toString().trim()

    private fun String?.notBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() && it != "null" }

    private fun isYouTubeHost(value: String): Boolean = runCatching {
        val uri = Uri.parse(value.trim())
        uri.scheme in setOf("http", "https") && uri.host?.lowercase(Locale.ROOT) in allowedHosts
    }.getOrDefault(false)
}
