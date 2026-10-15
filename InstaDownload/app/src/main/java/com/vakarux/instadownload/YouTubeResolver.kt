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

/** Public metadata for a YouTube link. This deliberately does not resolve media streams. */
data class YouTubeMetadata(
    val videoId: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val canonicalUrl: String,
    val description: String = "",
    val transcriptUrl: String? = null
)

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

    /** Uses oEmbed first, then best-effort public watch-page fields. Watch-page parsing may change or be blocked. */
    fun fetchMetadata(value: String): YouTubeMetadata {
        val id = extractVideoId(value) ?: throw IllegalArgumentException(
            "Paste a single YouTube video, Shorts or youtu.be link. Playlist/channel links are not supported."
        )
        val canonical = "https://www.youtube.com/watch?v=$id"
        val watch = runCatching { fetchWatchData(canonical) }.getOrNull()
        val oembed = runCatching { fetchOEmbed(canonical) }.getOrNull()

        val title = watch?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: oembed?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("YouTube did not expose public metadata for this video. It may be private, removed or temporarily unavailable.")
        val author = watch?.optString("author")?.trim()?.takeIf { it.isNotBlank() }
            ?: oembed?.optString("author_name")?.trim()?.takeIf { it.isNotBlank() }
            ?: "YouTube creator"
        val thumbnail = oembed?.optString("thumbnail_url")?.trim()?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val description = watch?.optString("description")?.trim().orEmpty()
        val transcriptUrl = watch?.optString("transcriptUrl")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        return YouTubeMetadata(id, title, author, thumbnail, canonical, description, transcriptUrl)
    }

    /** Fetches only publicly exposed caption tracks. It does not download or transcribe the protected audio stream. */
    fun fetchTranscript(info: YouTubeMetadata): String {
        val captionUrl = info.transcriptUrl ?: throw IllegalStateException(
            "No public caption track was exposed for this video. If captions are available, open the video in YouTube and enable them."
        )
        val request = Request.Builder()
            .url(captionUrl)
            .header("User-Agent", browserUserAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build()
        val transcript = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                throw IllegalStateException("YouTube captions could not be loaded (HTTP ${response.code}).")
            }
            val entries = Regex("""<text\b([^>]*)>(.*?)</text>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(body).mapNotNull { match ->
                    val raw = match.groupValues[2]
                    val decoded = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                        .replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
                    if (decoded.isBlank()) null else {
                        val start = Regex("""\bstart=[\"']([0-9.]+)[\"']""")
                            .find(match.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                        (start?.let { "${formatTimestamp(it)}  " }.orEmpty() + decoded)
                    }
                }.toList()
            if (entries.isEmpty()) throw IllegalStateException("The public caption track was empty or used an unsupported format.")
            entries.joinToString("\n")
        }
        return transcript
    }

    private fun fetchOEmbed(canonical: String): JSONObject {
        val encoded = URLEncoder.encode(canonical, StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://www.youtube.com/oembed?url=$encoded&format=json")
            .header("User-Agent", "InstaTubeToolkit/1.4 (Android; public metadata preview)")
            .get().build()
        val json = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(when (response.code) {
                    404, 401, 403 -> "YouTube did not make metadata available for this video (HTTP ${response.code}). It may be private, removed or not embeddable."
                    429 -> "YouTube is temporarily rate-limiting metadata requests. Try again later."
                    else -> "YouTube metadata request failed (HTTP ${response.code})."
                })
            }
            JSONObject(body)
        }
        return json
    }

    private fun fetchWatchData(canonical: String): JSONObject {
        val request = Request.Builder()
            .url(canonical)
            .header("User-Agent", browserUserAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build()
        val data = client.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            if (!response.isSuccessful || html.isBlank()) throw IllegalStateException("Watch page not available")
            val player = extractJsonObjectAfter(html, "ytInitialPlayerResponse")
            val details = player?.optJSONObject("videoDetails")
            val captions = player?.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks")
            val chosenTrack = captions?.let { array ->
                val tracks = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                tracks.firstOrNull { it.optString("languageCode").startsWith("en", ignoreCase = true) }
                    ?: tracks.firstOrNull()
            }
            val description = details?.optString("shortDescription")?.trim()?.takeIf { it.isNotBlank() }
                ?: metaContent(html, "og:description")
                ?: metaContent(html, "description")
                ?: ""
            JSONObject().apply {
                put("title", details?.optString("title")?.takeIf { !it.isNullOrBlank() } ?: metaContent(html, "og:title").orEmpty())
                put("author", details?.optString("author")?.takeIf { !it.isNullOrBlank() }.orEmpty())
                put("description", description)
                put("transcriptUrl", chosenTrack?.optString("baseUrl").orEmpty())
            }
        }
        return data
    }

    /** Extracts the balanced JSON literal after a JavaScript marker without truncating on braces in strings. */
    private fun extractJsonObjectAfter(html: String, marker: String): JSONObject? = runCatching {
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return@runCatching null
        val start = html.indexOf('{', markerIndex + marker.length)
        if (start < 0) return@runCatching null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return@runCatching JSONObject(html.substring(start, i + 1))
                    }
                }
            }
        }
        null
    }.getOrNull()

    private fun metaContent(html: String, sought: String): String? {
        val tags = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
        for (tag in tags) {
            val content = attr(tag.value, "content") ?: continue
            val name = attr(tag.value, "name") ?: attr(tag.value, "property") ?: continue
            if (name.equals(sought, ignoreCase = true)) {
                return Html.fromHtml(content, Html.FROM_HTML_MODE_LEGACY).toString().trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun attr(tag: String, name: String): String? = Regex(
        """(?i)\b${Regex.escape(name)}\s*=\s*(["'])(.*?)\1"""
    ).find(tag)?.groupValues?.getOrNull(2)

    private fun formatTimestamp(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        else String.format(Locale.ROOT, "%02d:%02d", minutes, secs)
    }

    private fun isYouTubeHost(value: String): Boolean = runCatching {
        val uri = Uri.parse(value.trim())
        uri.scheme in setOf("http", "https") && uri.host?.lowercase(Locale.ROOT) in allowedHosts
    }.getOrDefault(false)

    private const val browserUserAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
