package com.mariocart.app.data.server

import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object StreamExtractor {

    private const val TAG = "StreamExtractor"
    private const val TIMEOUT_MS = 25_000L

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    private val adDomains = setOf(
        "doubleclick", "googlesyndication", "adservice",
        "adnxs", "outbrain", "taboola", "popads", "popcash"
    )

    private val videoPatterns = listOf(
        // JSON file / src / link / url keys
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""link"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""link"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""stream"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        // playlist / stream
        Regex(""""playlist"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        // playerConfig
        Regex("""playerConfig\s*\.\s*file\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)""", RegexOption.IGNORE_CASE),
        Regex("""playerConfig\s*\.\s*file\s*=\s*["'](https?://[^"']+\.mp4[^"']*)""", RegexOption.IGNORE_CASE),
        // Playerjs
        Regex("""Playerjs\(\{[^}]*?file\s*:\s*["']?(https?://[^"'\s,}\]]+)""", RegexOption.IGNORE_CASE),
        // HLS.js
        Regex("""hls\.loadSource\(\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
        // Video.js sources array
        Regex(""""sources"\s*:\s*\[\s*\{\s*"src"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""", RegexOption.IGNORE_CASE),
        Regex(""""sources"\s*:\s*\[\s*\{\s*"src"\s*:\s*"(https?://[^"]+\.mp4[^"]*)"""", RegexOption.IGNORE_CASE),
        // <video src="..."> and <source src="...">
        Regex("""<(?:video|source)[^>]+src\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)""", RegexOption.IGNORE_CASE),
        Regex("""<(?:video|source)[^>]+src\s*=\s*["'](https?://[^"']+\.mp4[^"']*)""", RegexOption.IGNORE_CASE),
        // data-src / data-url attributes
        Regex("""data-(?:src|file|url|hls)\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)""", RegexOption.IGNORE_CASE),
        Regex("""data-(?:src|file|url|hls)\s*=\s*["'](https?://[^"']+\.mp4[^"']*)""", RegexOption.IGNORE_CASE),
        // JWPlayer setup
        Regex("""jwplayer\s*\([^)]+\)\s*\.setup\s*\([^)]*"file"\s*:\s*"(https?://[^"]+)""", RegexOption.IGNORE_CASE),
        // var/let/const videoUrl = ...
        Regex("""(?:var|let|const)\s+\w*(?:url|src|file|stream|video)\w*\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)""", RegexOption.IGNORE_CASE),
        Regex("""(?:var|let|const)\s+\w*(?:url|src|file|stream|video)\w*\s*=\s*["'](https?://[^"']+\.mp4[^"']*)""", RegexOption.IGNORE_CASE),
        // Common path pattern: master.m3u8, index.m3u8, playlist.m3u8
        Regex("""["'](https?://[^"'\s]+/(?:master|index|playlist|media)\.m3u8[^"'\s]*)["']""", RegexOption.IGNORE_CASE),
        // Generic bare URLs (last resort)
        Regex("""(https?://[^\s"'<>()\]]+\.m3u8(?:\?[^\s"'<>()\]]*)?)""", RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.mp4(?:\?[^\s"'<>()\]]*)?)""", RegexOption.IGNORE_CASE),
    )

    // ── Main entry ────────────────────────────────────────────────────────────

    suspend fun extract(embedUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val host = Uri.parse(embedUrl).host?.lowercase() ?: ""
            Log.d(TAG, "Extracting: $embedUrl")

            val result = when {
                host.contains("vidsrc.me")  || host.contains("vsembed")  -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.io")                                -> extractVidSrcQueryParam(embedUrl)
                host.contains("vidsrc.pm")                                -> extractVidSrcQueryParam(embedUrl)
                host.contains("vidsrc.to")                                -> extractVidSrcTo(embedUrl)
                host.contains("vidsrc.dev") || host.contains("vidsrc.in")
                    || host.contains("vidsrc.nl") || host.contains("vidsrc.su")
                    || host.contains("vidsrc.lol") || host.contains("vidsrc2") -> extractVidSrcFamily(embedUrl)
                host.contains("vidlink")                                  -> extractVidLink(embedUrl)
                host.contains("videasy")                                  -> extractVideasy(embedUrl)
                host.contains("autoembed")                                -> extractAutoEmbed(embedUrl)
                host.contains("embed.su")                                 -> extractEmbedSu(embedUrl)
                host.contains("vidbinge")                                 -> extractVidBinge(embedUrl)
                host.contains("2embed")                                   -> extractGenericWithApi(embedUrl)
                host.contains("embedrise")                                -> scrapeUrl(embedUrl)
                host.contains("multiembed")                               -> extractMultiEmbed(embedUrl)
                else                                                      -> scrapeUrl(embedUrl)
            }

            if (result != null) Log.d(TAG, "✅ Found: $result")
            else Log.d(TAG, "❌ Nothing found for $embedUrl")
            result
        } catch (e: Exception) {
            Log.e(TAG, "extract() error for $embedUrl: ${e.message}")
            null
        }
    }

    // ── Server-specific extractors ────────────────────────────────────────────

    /**
     * VidSrc.me / VidSrc.dev / VidSrc.nl / vsembed.ru
     * Grabs data-i then hits internal AJAX APIs.
     */
    private fun extractVidSrcFamily(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }
        tryBase64Urls(html)?.let { return it }

        val dataI = Regex("""data-i\s*=\s*["']?(\w+)""").find(html)?.groupValues?.get(1)
            ?: return followIframes(html, pageUrl)

        val origin = pageUrl.toOrigin()
        Log.d(TAG, "VidSrc data-i=$dataI")

        val ajaxPaths = listOf(
            "$origin/ajax/embed/episode?id=$dataI",
            "$origin/ajax/embed/movie?id=$dataI",
            "$origin/ajax/v2/embed/episode?id=$dataI",
            "$origin/ajax/v2/embed/movie?id=$dataI",
            "$origin/ajax/sources/$dataI",
            "$origin/api/source/$dataI",
            "$origin/api/v2/source/$dataI",
            "$origin/api/v3/source/$dataI",
            "$origin/api/source?id=$dataI",
            "$origin/api/v2/source?id=$dataI",
            // vsembed mirrors
            "https://vsembed.ru/ajax/embed/episode?id=$dataI",
            "https://vsembed.ru/ajax/embed/movie?id=$dataI",
            "https://vidsrc.me/ajax/embed/episode?id=$dataI",
            "https://vidsrc.me/ajax/embed/movie?id=$dataI",
        )
        for (path in ajaxPaths) {
            val resp = fetchJson(path, referer = pageUrl) ?: continue
            findVideoUrl(resp)?.let { return it }
            tryBase64Urls(resp)?.let { return it }
        }
        return followIframes(html, pageUrl)
    }

    /**
     * VidSrc.io / VidSrc.pm — use ?tmdb= query param style.
     * Falls back to path-style if query param doesn't yield a stream.
     */
    private fun extractVidSrcQueryParam(pageUrl: String): String? {
        // Try the page directly first
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }
        tryBase64Urls(html)?.let { return it }

        // Try alternative URL forms
        val uri = Uri.parse(pageUrl)
        val segs = uri.pathSegments
        val tmdbId = uri.getQueryParameter("tmdb")
            ?: segs.lastOrNull { it.all(Char::isDigit) }

        if (tmdbId != null) {
            val origin = pageUrl.toOrigin()
            val altPaths = listOf(
                "$origin/ajax/embed/movie?id=$tmdbId",
                "$origin/api/source/$tmdbId",
                "$origin/api/v2/source/$tmdbId",
            )
            for (path in altPaths) {
                val resp = fetchJson(path, referer = pageUrl) ?: continue
                findVideoUrl(resp)?.let { return it }
            }
        }

        val dataI = Regex("""data-i\s*=\s*["']?(\w+)""").find(html)?.groupValues?.get(1)
        if (dataI != null) {
            val origin = pageUrl.toOrigin()
            for (path in listOf(
                "$origin/ajax/embed/episode?id=$dataI",
                "$origin/ajax/embed/movie?id=$dataI",
                "$origin/api/source/$dataI",
            )) {
                val resp = fetchJson(path, referer = pageUrl) ?: continue
                findVideoUrl(resp)?.let { return it }
            }
        }

        return followIframes(html, pageUrl)
    }

    /**
     * VidSrc.to — resolves through vsembed mirror.
     */
    private fun extractVidSrcTo(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }

        val dataI = Regex("""data-i\s*=\s*["']?(\w+)""").find(html)?.groupValues?.get(1)
        if (dataI != null) {
            val origin = pageUrl.toOrigin()
            val ajaxPaths = listOf(
                "$origin/ajax/embed/episode?id=$dataI",
                "$origin/ajax/embed/movie?id=$dataI",
                "$origin/api/source/$dataI",
                "https://vsembed.ru/ajax/embed/episode?id=$dataI",
                "https://vsembed.ru/ajax/embed/movie?id=$dataI",
                "https://vidsrc.me/ajax/embed/episode?id=$dataI",
                "https://vidsrc.me/ajax/embed/movie?id=$dataI",
            )
            for (path in ajaxPaths) {
                val resp = fetchJson(path, referer = pageUrl) ?: continue
                findVideoUrl(resp)?.let { return it }
                tryBase64Urls(resp)?.let { return it }
            }
        }
        return followIframes(html, pageUrl)
    }

    /**
     * VidLink.pro — `/api/b/movie/{id}` returns JSON with a playlist field.
     */
    private fun extractVidLink(pageUrl: String): String? {
        val uri = Uri.parse(pageUrl)
        val segs = uri.pathSegments
        val apiUrl = when {
            segs.size >= 2 && segs[0] == "movie" ->
                "https://vidlink.pro/api/b/movie/${segs[1]}"
            segs.size >= 4 && segs[0] == "tv" ->
                "https://vidlink.pro/api/b/tv/${segs[1]}/${segs[2]}/${segs[3]}"
            segs.size >= 3 && segs[1] == "movie" ->
                "https://vidlink.pro/api/b/movie/${segs[2]}"
            segs.size >= 5 && segs[1] == "tv" ->
                "https://vidlink.pro/api/b/tv/${segs[2]}/${segs[3]}/${segs[4]}"
            else -> null
        }
        if (apiUrl != null) {
            Log.d(TAG, "VidLink API: $apiUrl")
            val resp = fetchJson(apiUrl, referer = pageUrl)
            if (resp != null) {
                Regex(""""playlist"\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                    .find(resp)?.groupValues?.get(1)?.let { return it }
                Regex(""""stream_url"\s*:\s*["']([^"']+)["']""")
                    .find(resp)?.groupValues?.get(1)?.takeIf { isValidVideo(it) }?.let { return it }
                findVideoUrl(resp)?.let { return it }
            }
        }
        return scrapeUrl(pageUrl)
    }

    /**
     * Videasy — player endpoint returns HTML or JSON with video config.
     */
    private fun extractVideasy(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(pageUrl)?.groupValues?.get(1)
        if (tmdbId != null) {
            val isMovie = pageUrl.contains("/movie/")
            val baseApis = listOf(
                if (isMovie) "https://player.videasy.net/api/movie/$tmdbId"
                else {
                    val s = Regex("""/tv/\d+/(\d+)/(\d+)""").find(pageUrl)
                    if (s != null) "https://player.videasy.net/api/tv/$tmdbId/${s.groupValues[1]}/${s.groupValues[2]}"
                    else "https://player.videasy.net/api/tv/$tmdbId/1/1"
                }
            )
            for (apiUrl in baseApis) {
                fetchJson(apiUrl, referer = pageUrl)?.let { resp ->
                    findVideoUrl(resp)?.let { return it }
                }
            }
        }
        return followIframes(html, pageUrl)
    }

    /**
     * AutoEmbed — has a known /api/v2/ pattern.
     */
    private fun extractAutoEmbed(pageUrl: String): String? {
        val segs = Uri.parse(pageUrl).pathSegments
        val type = when {
            segs.contains("movie") -> "movie"
            segs.contains("tv")    -> "tv"
            else -> null
        }
        val id = segs.lastOrNull { it.all(Char::isDigit) }
        if (type != null && id != null) {
            for (base in listOf("https://autoembed.cc", "https://autoembed.co")) {
                fetchJson("$base/api/v2/$type/$id", referer = pageUrl)?.let { resp ->
                    findVideoUrl(resp)?.let { return it }
                }
            }
        }
        return scrapeUrl(pageUrl)
    }    }

    /**
     * Embed.su — POST to their AJAX API to get sources.
     */
    private fun extractEmbedSu(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }

        // Extract the source ID from the page
        val sourceId = Regex("""data-id\s*=\s*["']?([^"'\s]+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""embed\.su/embed/(?:movie|tv)/(\d+)""").find(pageUrl)?.groupValues?.get(1)

        if (sourceId != null) {
            val body = """{"id":"$sourceId"}""".toRequestBody("application/json".toMediaType())
            val resp = fetchPost("https://embed.su/ajax/api/source", body, referer = pageUrl)
            if (resp != null) {
                findVideoUrl(resp)?.let { return it }
            }
        }
        return followIframes(html, pageUrl)
    }

    /**
     * VidBinge — public REST API at /api/v1/.
     */
    private fun extractVidBinge(pageUrl: String): String? {
        val uri = Uri.parse(pageUrl)
        val segs = uri.pathSegments
        val type = when {
            segs.contains("movie") -> "movie"
            segs.contains("tv")    -> "tv"
            else -> null
        }
        val id = segs.lastOrNull { it.all(Char::isDigit) }
        if (type != null && id != null) {
            val apiUrl = "https://vidbinge.dev/api/v1/$type/$id"
            fetchJson(apiUrl, referer = pageUrl)?.let { resp ->
                findVideoUrl(resp)?.let { return it }
            }
        }
        return scrapeUrl(pageUrl)
    }

    /**
     * MultiEmbed — uses a direct API pattern.
     */
    private fun extractMultiEmbed(pageUrl: String): String? {
        val uri = Uri.parse(pageUrl)
        val tmdbId = uri.getQueryParameter("tmdb")
            ?: Uri.parse(pageUrl).pathSegments.lastOrNull { it.all(Char::isDigit) }
        val type = when {
            pageUrl.contains("/movie/") || uri.getQueryParameter("type") == "movie" -> "movie"
            pageUrl.contains("/tv/")    || uri.getQueryParameter("type") == "tv"    -> "tv"
            else -> "movie"
        }
        if (tmdbId != null) {
            val apiUrl = "https://multiembed.mov/api/$type/$tmdbId"
            fetchJson(apiUrl, referer = pageUrl)?.let { resp ->
                findVideoUrl(resp)?.let { return it }
            }
        }
        return scrapeUrl(pageUrl)
    }

    /**
     * Generic provider with possible /api/ endpoints.
     */
    private fun extractGenericWithApi(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }
        tryBase64Urls(html)?.let { return it }
        return followIframes(html, pageUrl)
    }

    // ── Generic scraper ───────────────────────────────────────────────────────

    private fun scrapeUrl(url: String): String? {
        val html = fetch(url) ?: return null
        findVideoUrl(html)?.let { return it }
        tryBase64Urls(html)?.let { return it }
        return followIframes(html, url)
    }

    private fun followIframes(html: String, parentUrl: String, depth: Int = 0): String? {
        if (depth > 2) return null
        val re = Regex("""<iframe[^>]+src=["']?(https?://[^"'\s>]+)""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(html)) {
            val src = m.groupValues[1]
            if (isAdUrl(src)) continue
            val child = fetch(src) ?: continue
            findVideoUrl(child)?.let { return it }
            tryBase64Urls(child)?.let { return it }
            followIframes(child, src, depth + 1)?.let { return it }
        }
        return null
    }

    // ── URL finders ───────────────────────────────────────────────────────────

    private fun findVideoUrl(html: String): String? {
        for (pattern in videoPatterns) {
            for (match in pattern.findAll(html)) {
                val url = (match.groupValues.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() } ?: match.value).trim()
                if (isValidVideo(url)) return url
            }
        }
        return null
    }

    private fun tryBase64Urls(html: String): String? {
        // atob("...") calls
        Regex("""atob\(\s*["']([A-Za-z0-9+/=]{20,})["']\s*\)""").findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (isValidVideo(decoded)) return decoded
                // decoded might be another URL to fetch
                if (decoded.startsWith("http")) {
                    val resp = fetch(decoded) ?: return@forEach
                    findVideoUrl(resp)?.let { return it }
                }
            } catch (_: Exception) { }
        }
        // Standalone long base64 strings that decode to http URLs
        Regex("""["']([A-Za-z0-9+/]{60,}={0,2})["']""").findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (decoded.startsWith("http") && isValidVideo(decoded)) return decoded
            } catch (_: Exception) { }
        }
        return null
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun fetch(url: String, referer: String? = null): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer ?: url)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "iframe")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
            .build()
        val resp = client.newCall(req).execute()
        val body = if (resp.isSuccessful) resp.body?.string() else null
        resp.close()
        body
    } catch (_: Exception) { null }

    private fun fetchJson(url: String, referer: String? = null): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer ?: url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", url.toOrigin())
            .build()
        val resp = client.newCall(req).execute()
        val body = if (resp.isSuccessful) resp.body?.string() else null
        resp.close()
        body
    } catch (_: Exception) { null }

    private fun fetchPost(url: String, body: okhttp3.RequestBody, referer: String? = null): String? = try {
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", UA)
            .header("Referer", referer ?: url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", url.toOrigin())
            .build()
        val resp = client.newCall(req).execute()
        val result = if (resp.isSuccessful) resp.body?.string() else null
        resp.close()
        result
    } catch (_: Exception) { null }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun isValidVideo(url: String): Boolean {
        if (url.isBlank() || url.length < 20) return false
        val lower = url.lowercase()
        return (lower.contains(".m3u8") || lower.contains(".mp4")) && !isAdUrl(url)
    }

    private fun isAdUrl(url: String): Boolean {
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        return adDomains.any { host.contains(it) }
    }

    private fun String.toOrigin(): String = try {
        val u = Uri.parse(this)
        "${u.scheme}://${u.host}"
    } catch (_: Exception) { this }
}
