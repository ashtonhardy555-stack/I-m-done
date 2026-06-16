package com.mariocart.app.data.server

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

/**
 * Extracts a direct .m3u8 or .mp4 video URL from a streaming embed URL.
 * The returned URL is played directly in ExoPlayer — no WebView involved.
 *
 * Strategy (in order):
 *  1. Known REST APIs  -> call JSON endpoint directly (fastest, no ads)
 *  2. VidSrc family   -> hit their AJAX endpoints after one page fetch
 *  3. Everything else -> probe 10 common API patterns, then scrape HTML
 */
object StreamExtractor {

    private const val TAG = "StreamExtractor"
    private const val TIMEOUT_MS = 18_000L

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val blockedDomains = setOf(
        "doubleclick", "googlesyndication", "adservice", "adnxs", "adroll",
        "outbrain", "taboola", "popads", "popcash", "popunder", "propellerads",
        "mgid", "revcontent", "exoclick", "trafficjunky", "juicyads",
        "adsterra", "clickadu", "hilltopads", "trafficstars", "richpush",
        "pushground", "megapu", "onclicka", "rotator", "adcash",
        "adf.ly", "linkvertise", "shrink.pe", "exe.io", "fc.lc",
        "coinhive", "crypto-loot", "coinblockers"
    )

    private val priorityJsonKeys = setOf(
        "playlist", "file", "src", "url", "stream", "hls", "m3u8",
        "videoUrl", "video_url", "streamUrl", "stream_url",
        "source", "link", "direct", "mp4", "720p", "1080p", "480p", "360p"
    )

    private val genericMoviePatterns = listOf(
        "{origin}/api/v2/movie/{id}",
        "{origin}/api/v1/movie/{id}",
        "{origin}/api/movie/{id}",
        "{origin}/api/b/movie/{id}",
        "{origin}/api/source/movie/{id}",
        "{origin}/api/stream/movie/{id}",
        "{origin}/api/embed/movie/{id}",
        "{origin}/ajax/embed/movie?id={id}",
        "{origin}/ajax/sources/{id}",
        "{origin}/api/source/{id}"
    )

    private val genericTvPatterns = listOf(
        "{origin}/api/v2/tv/{id}?season={s}&episode={e}",
        "{origin}/api/v1/tv/{id}?season={s}&episode={e}",
        "{origin}/api/tv/{id}/{s}/{e}",
        "{origin}/api/b/tv/{id}/{s}/{e}",
        "{origin}/api/stream/tv/{id}/{s}/{e}",
        "{origin}/api/embed/tv/{id}/{s}/{e}",
        "{origin}/ajax/embed/episode?id={id}&s={s}&e={e}"
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .addInterceptor(AdBlockInterceptor())
        .build()

    private class AdBlockInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host.lowercase()
            if (blockedDomains.any { host.contains(it) }) {
                Log.d(TAG, "Blocked ad domain: $host")
                val emptyBody = ByteArray(0).toResponseBody(null)
                return Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("Blocked")
                    .body(emptyBody)
                    .build()
            }
            return chain.proceed(chain.request())
        }
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    suspend fun extract(embedUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val host = Uri.parse(embedUrl).host?.lowercase() ?: ""
            Log.d(TAG, "Extracting from: $embedUrl")

            val result = when {
                host.contains("vidlink")                            -> extractVidLink(embedUrl)
                host.contains("videasy")                           -> extractVideasy(embedUrl)
                host.contains("autoembed") && host.contains("cc") -> extractAutoEmbed(embedUrl, "cc")
                host.contains("autoembed") && host.contains("co") -> extractAutoEmbed(embedUrl, "co")
                host.contains("autoembed")                         -> extractAutoEmbed(embedUrl, "cc")
                host.contains("vidbinge")                          -> extractVidBinge(embedUrl)
                host.contains("moviesapi")                         -> extractMoviesApi(embedUrl)
                host.contains("vidsrc.me") || host.contains("vsembed") -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.io")                         -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.pm")                         -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.to")                         -> extractVidSrcTo(embedUrl)
                host.contains("vidsrc")                            -> extractVidSrcFamily(embedUrl)
                else                                               -> extractGeneric(embedUrl)
            }

            if (result != null) Log.d(TAG, "Found stream: $result")
            else Log.d(TAG, "No stream found for: $embedUrl")

            result
        } catch (e: Exception) {
            Log.e(TAG, "Extractor crashed for $embedUrl: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Tier 1 — known REST API servers
    // -------------------------------------------------------------------------

    private fun extractVidLink(url: String): String? {
        val segs = Uri.parse(url).pathSegments
        val apiUrl = when {
            segs.contains("movie") -> {
                val id = segs.getOrNull(segs.indexOf("movie") + 1) ?: return null
                "https://vidlink.pro/api/b/movie/$id"
            }
            segs.contains("tv") -> {
                val i = segs.indexOf("tv")
                val id = segs.getOrNull(i + 1) ?: return null
                val s = segs.getOrElse(i + 2) { "1" }
                val e = segs.getOrElse(i + 3) { "1" }
                "https://vidlink.pro/api/b/tv/$id/$s/$e"
            }
            else -> return null
        }
        Log.d(TAG, "VidLink -> $apiUrl")
        return fetchJson(apiUrl, referer = url)?.let { parseJson(it) }
    }

    private fun extractVideasy(url: String): String? {
        val segs = Uri.parse(url).pathSegments
        val nums = segs.filter { it.all(Char::isDigit) }
        val id = nums.firstOrNull() ?: return null
        val apiUrl = if (url.contains("/movie/")) {
            "https://player.videasy.net/api/movie/$id"
        } else {
            val s = nums.getOrElse(1) { "1" }
            val e = nums.getOrElse(2) { "1" }
            "https://player.videasy.net/api/tv/$id/$s/$e"
        }
        Log.d(TAG, "Videasy -> $apiUrl")
        return fetchJson(apiUrl, referer = url)?.let { parseJson(it) }
    }

    private fun extractAutoEmbed(url: String, tld: String): String? {
        val uri = Uri.parse(url)
        val segs = uri.pathSegments
        val id = segs.lastOrNull { it.all(Char::isDigit) }
            ?: uri.getQueryParameter("id") ?: return null
        val apiUrl = if (segs.contains("movie")) {
            "https://autoembed.$tld/api/v2/movie/$id"
        } else {
            val s = uri.getQueryParameter("s")
                ?: uri.getQueryParameter("season")
                ?: segs.filter { it.all(Char::isDigit) }.getOrElse(1) { "1" }
            val e = uri.getQueryParameter("e")
                ?: uri.getQueryParameter("episode")
                ?: segs.filter { it.all(Char::isDigit) }.getOrElse(2) { "1" }
            "https://autoembed.$tld/api/v2/tv/$id?season=$s&episode=$e"
        }
        Log.d(TAG, "AutoEmbed.$tld -> $apiUrl")
        return fetchJson(apiUrl, referer = url)?.let { parseJson(it) }
    }

    private fun extractVidBinge(url: String): String? {
        val segs = Uri.parse(url).pathSegments
        val nums = segs.filter { it.all(Char::isDigit) }
        val id = nums.firstOrNull() ?: return null
        val apiUrl = if (url.contains("movie")) {
            "https://vidbinge.dev/api/movie/$id"
        } else {
            val s = nums.getOrElse(1) { "1" }
            val e = nums.getOrElse(2) { "1" }
            "https://vidbinge.dev/api/tv/$id/$s/$e"
        }
        Log.d(TAG, "VidBinge -> $apiUrl")
        return fetchJson(apiUrl, referer = url)?.let { parseJson(it) }
    }

    private fun extractMoviesApi(url: String): String? {
        val uri = Uri.parse(url)
        val id = uri.pathSegments.lastOrNull { it.all(Char::isDigit) } ?: return null
        val apiUrl = if (url.contains("movie")) {
            "https://moviesapi.club/api/v2/movie/$id"
        } else {
            val s = uri.getQueryParameter("s") ?: "1"
            val e = uri.getQueryParameter("e") ?: "1"
            "https://moviesapi.club/api/v2/tv/$id?season=$s&episode=$e"
        }
        Log.d(TAG, "MoviesAPI -> $apiUrl")
        return fetchJson(apiUrl, referer = url)?.let { parseJson(it) }
    }

    // -------------------------------------------------------------------------
    // Tier 2 — VidSrc family (AJAX endpoints)
    // -------------------------------------------------------------------------

    private fun extractVidSrcFamily(url: String): String? {
        val html = fetch(url) ?: return null
        findVideoInHtml(html)?.let { return it }
        tryBase64(html)?.let { return it }

        val dataI = Regex("""data-i\s*=\s*["']?(\w+)""").find(html)?.groupValues?.get(1)
            ?: return followIframes(html, url)

        val origin = url.toOrigin()
        val ajaxPaths = listOf(
            "$origin/ajax/embed/movie?id=$dataI",
            "$origin/ajax/embed/episode?id=$dataI",
            "$origin/ajax/v2/embed/movie?id=$dataI",
            "$origin/ajax/v2/embed/episode?id=$dataI",
            "$origin/ajax/sources/$dataI",
            "$origin/api/source/$dataI",
            "$origin/api/v2/source/$dataI"
        )
        for (path in ajaxPaths) {
            fetchJson(path, referer = url)?.let { parseJson(it) }?.let { return it }
        }
        return followIframes(html, url)
    }

    private fun extractVidSrcTo(url: String): String? {
        val html = fetch(url) ?: return null
        findVideoInHtml(html)?.let { return it }

        val dataI = Regex("""data-i\s*=\s*["']?(\w+)""").find(html)?.groupValues?.get(1)
        if (dataI != null) {
            val origin = url.toOrigin()
            val ajaxPaths = listOf(
                "$origin/ajax/embed/movie?id=$dataI",
                "$origin/ajax/embed/episode?id=$dataI",
                "$origin/api/source/$dataI",
                "https://vsembed.ru/ajax/embed/movie?id=$dataI",
                "https://vsembed.ru/ajax/embed/episode?id=$dataI",
                "https://vidsrc.me/ajax/embed/movie?id=$dataI"
            )
            for (path in ajaxPaths) {
                fetchJson(path, referer = url)?.let { parseJson(it) }?.let { return it }
            }
        }
        return followIframes(html, url)
    }

    // -------------------------------------------------------------------------
    // Tier 3 — generic API probe + HTML scrape
    // -------------------------------------------------------------------------

    private fun extractGeneric(url: String): String? {
        val uri = Uri.parse(url)
        val origin = url.toOrigin()
        val segs = uri.pathSegments
        val id = segs.lastOrNull { it.all(Char::isDigit) }
            ?: uri.getQueryParameter("id")
            ?: uri.getQueryParameter("tmdb")

        val isMovie = url.contains("movie", ignoreCase = true)
        val s = uri.getQueryParameter("s")
            ?: uri.getQueryParameter("season")
            ?: segs.filter { it.all(Char::isDigit) }.getOrElse(1) { "1" }
        val e = uri.getQueryParameter("e")
            ?: uri.getQueryParameter("episode")
            ?: segs.filter { it.all(Char::isDigit) }.getOrElse(2) { "1" }

        if (id != null) {
            val patterns = if (isMovie) genericMoviePatterns else genericTvPatterns
            for (pattern in patterns) {
                val apiUrl = pattern
                    .replace("{origin}", origin)
                    .replace("{id}", id)
                    .replace("{s}", s)
                    .replace("{e}", e)
                fetchJson(apiUrl, referer = url)?.let { parseJson(it) }?.let { return it }
            }
        }

        val html = fetch(url) ?: return null
        findVideoInHtml(html)?.let { return it }
        tryBase64(html)?.let { return it }
        return followIframes(html, url)
    }

    // -------------------------------------------------------------------------
    // JSON parsing — recursive traversal
    // -------------------------------------------------------------------------

    private fun parseJson(raw: String): String? {
        return try {
            findInElement(JsonParser.parseString(raw))
        } catch (e: JsonSyntaxException) {
            findVideoInHtml(raw)
        }
    }

    private fun findInElement(el: JsonElement, depth: Int = 0): String? {
        if (depth > 12) return null
        return when {
            el.isJsonPrimitive -> {
                try {
                    val str = el.asString
                    if (isValidVideo(str)) str else null
                } catch (e: Exception) {
                    null
                }
            }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                for (key in priorityJsonKeys) {
                    val found = obj.get(key)?.let { findInElement(it, depth + 1) }
                    if (found != null) return found
                }
                for ((k, v) in obj.entrySet()) {
                    if (k !in priorityJsonKeys) {
                        val found = findInElement(v, depth + 1)
                        if (found != null) return found
                    }
                }
                null
            }
            el.isJsonArray -> {
                for (child in el.asJsonArray) {
                    val found = findInElement(child, depth + 1)
                    if (found != null) return found
                }
                null
            }
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // HTML scraping
    // -------------------------------------------------------------------------

    private val htmlPatterns = listOf(
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""",  RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",  RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""",   RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",  RegexOption.IGNORE_CASE),
        Regex(""""playlist"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex("""hls\.loadSource\(\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",   RegexOption.IGNORE_CASE),
        Regex("""data-(?:src|file|url|hls)\s*=\s*["'](https?://[^"']+\.m3u8)""", RegexOption.IGNORE_CASE),
        Regex("""["'](https?://[^"'\s]+/(?:master|index|playlist)\.m3u8[^"'\s]*)["']""", RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.m3u8(?:\?[^\s"'<>()\]]*)?)""",        RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.mp4(?:\?[^\s"'<>()\]]*)?)""",         RegexOption.IGNORE_CASE)
    )

    private fun findVideoInHtml(html: String): String? {
        val jsonBlockRe = Regex(
            """(?:sources|playlist|streams)\s*[:=]\s*(\[.*?]|\{.*?})""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in jsonBlockRe.findAll(html)) {
            parseJson(m.groupValues[1])?.let { return it }
        }
        for (pat in htmlPatterns) {
            for (m in pat.findAll(html)) {
                val candidate = m.groupValues.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: m.value.trim()
                if (isValidVideo(candidate)) return candidate
            }
        }
        return null
    }

    private fun tryBase64(html: String): String? {
        Regex("""atob\(\s*["']([A-Za-z0-9+/=]{20,})["']\s*\)""").findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (isValidVideo(decoded)) return decoded
            } catch (e: Exception) { /* skip */ }
        }
        Regex("""["']([A-Za-z0-9+/]{60,}={0,2})["']""").findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (decoded.startsWith("http") && isValidVideo(decoded)) return decoded
            } catch (e: Exception) { /* skip */ }
        }
        return null
    }

    private fun followIframes(html: String, parentUrl: String, depth: Int = 0): String? {
        if (depth > 2) return null
        val iframeRe = Regex("""<iframe[^>]+src=["']?(https?://[^"'\s>]+)""", RegexOption.IGNORE_CASE)
        for (m in iframeRe.findAll(html)) {
            val src = m.groupValues[1]
            val host = Uri.parse(src).host?.lowercase() ?: continue
            if (blockedDomains.any { host.contains(it) }) continue
            val child = fetch(src) ?: continue
            findVideoInHtml(child)?.let { return it }
            tryBase64(child)?.let { return it }
            followIframes(child, src, depth + 1)?.let { return it }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private fun fetch(url: String, referer: String? = null): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer ?: url)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "iframe")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "cross-site")
                .build()
            val response = client.newCall(request).execute()
            val body = if (response.isSuccessful) {
                response.body?.string()
            } else {
                Log.d(TAG, "fetch $url -> ${response.code}")
                null
            }
            response.close()
            body
        } catch (e: Exception) {
            Log.d(TAG, "fetch error $url: ${e.message}")
            null
        }
    }

    private fun fetchJson(url: String, referer: String? = null): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer ?: url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", url.toOrigin())
                .build()
            val response = client.newCall(request).execute()
            val body = if (response.isSuccessful) {
                response.body?.string()
            } else {
                Log.d(TAG, "fetchJson $url -> ${response.code}")
                null
            }
            response.close()
            body
        } catch (e: Exception) {
            Log.d(TAG, "fetchJson error $url: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun isValidVideo(url: String): Boolean {
        if (url.isBlank() || url.length < 20 || !url.startsWith("http")) return false
        val lower = url.lowercase()
        if (!lower.contains(".m3u8") && !lower.contains(".mp4")) return false
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return blockedDomains.none { host.contains(it) }
    }

    private fun String.toOrigin(): String {
        return try {
            val uri = Uri.parse(this)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            this
        }
    }
}
