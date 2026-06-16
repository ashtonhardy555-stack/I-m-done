package com.mariocart.app.data.server

import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

object StreamExtractor {

    private const val TAG = "StreamExtractor"
    private const val TIMEOUT_S = 8L

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val cookieJar = JavaNetCookieJar(
        CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    private val adDomains = setOf(
        "doubleclick", "googlesyndication", "adservice",
        "adnxs", "outbrain", "taboola", "popads", "popcash"
    )

    private val videoPatterns = listOf(
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",    RegexOption.IGNORE_CASE),
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""",     RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",     RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""",      RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",     RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""",      RegexOption.IGNORE_CASE),
        Regex(""""playlist"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",RegexOption.IGNORE_CASE),
        Regex(""""stream"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",  RegexOption.IGNORE_CASE),
        Regex("""hls\.loadSource\(\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",      RegexOption.IGNORE_CASE),
        Regex(""""sources"\s*:\s*\[\s*\{\s*"src"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""",RegexOption.IGNORE_CASE),
        Regex("""data-(?:src|file|url|hls)\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)""",RegexOption.IGNORE_CASE),
        Regex("""jwplayer\s*\([^)]+\)\s*\.setup\s*\([^)]*"file"\s*:\s*"(https?://[^"]+)""",RegexOption.IGNORE_CASE),
        Regex("""Playerjs\(\{[^}]*?file\s*:\s*["']?(https?://[^"'\s,}\]]+)""",      RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.m3u8(?:\?[^\s"'<>()\]]*)?)""",           RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.mp4(?:\?[^\s"'<>()\]]*)?)""",            RegexOption.IGNORE_CASE),
        Regex("""["'](https?://[^"'\s]+/(?:master|index|playlist)\.m3u8[^"'\s]*)["']""",RegexOption.IGNORE_CASE),
    )

    // ── PUBLIC: try every known direct JSON API — call this ONCE before the server loop ──

    suspend fun extractDirect(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        val isMovie = contentType == "movie"
        Log.d(TAG, "extractDirect id=$tmdbId type=$contentType s=$season e=$episode")

        // VidLink.pro
        fetchJson(
            if (isMovie) "https://vidlink.pro/api/b/movie/$tmdbId"
            else         "https://vidlink.pro/api/b/tv/$tmdbId/$season/$episode",
            referer = if (isMovie) "https://vidlink.pro/movie/$tmdbId"
                      else         "https://vidlink.pro/tv/$tmdbId/$season/$episode"
        )?.let { r ->
            Regex(""""playlist"\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(r)
                ?.groupValues?.get(1)?.let { Log.d(TAG,"✅ VidLink: $it"); return@withContext it }
            findVideoUrl(r)?.let { Log.d(TAG,"✅ VidLink: $it"); return@withContext it }
        }

        // Videasy
        fetchJson(
            if (isMovie) "https://player.videasy.net/api/movie/$tmdbId"
            else         "https://player.videasy.net/api/tv/$tmdbId/$season/$episode",
            referer = "https://player.videasy.net"
        )?.let { r ->
            findVideoUrl(r)?.let { Log.d(TAG,"✅ Videasy: $it"); return@withContext it }
            Regex(""""(?:hls|stream|playlist|url)"\s*:\s*"(https?://[^"]+)"""",RegexOption.IGNORE_CASE)
                .find(r)?.groupValues?.get(1)?.takeIf { isValidVideo(it) }
                ?.let { Log.d(TAG,"✅ Videasy: $it"); return@withContext it }
        }

        // AutoEmbed.cc
        for (base in listOf("https://autoembed.cc","https://autoembed.co")) {
            fetchJson(
                if (isMovie) "$base/api/v2/movie/$tmdbId"
                else         "$base/api/v2/tv/$tmdbId/$season/$episode",
                referer = base
            )?.let { r ->
                findVideoUrl(r)?.let { Log.d(TAG,"✅ AutoEmbed: $it"); return@withContext it }
            }
        }

        // SuperEmbed
        fetchJson(
            if (isMovie) "https://superembed.stream/api/v2/movie/$tmdbId"
            else         "https://superembed.stream/api/v2/tv/$tmdbId/$season/$episode",
            referer = "https://superembed.stream"
        )?.let { r ->
            findVideoUrl(r)?.let { Log.d(TAG,"✅ SuperEmbed: $it"); return@withContext it }
        }

        // VidBinge
        fetchJson(
            if (isMovie) "https://vidbinge.dev/api/v2/movie?id=$tmdbId"
            else         "https://vidbinge.dev/api/v2/tv?id=$tmdbId&s=$season&e=$episode",
            referer = "https://vidbinge.dev"
        )?.let { r ->
            findVideoUrl(r)?.let { Log.d(TAG,"✅ VidBinge: $it"); return@withContext it }
        }

        // MoviesAPI
        fetchJson(
            if (isMovie) "https://moviesapi.club/api/v2/movie/$tmdbId"
            else         "https://moviesapi.club/api/v2/tv/$tmdbId/$season/$episode",
            referer = "https://moviesapi.club"
        )?.let { r ->
            findVideoUrl(r)?.let { Log.d(TAG,"✅ MoviesAPI: $it"); return@withContext it }
        }

        // embed.su
        fetchJson(
            if (isMovie) "https://embed.su/api/source/$tmdbId"
            else         "https://embed.su/api/source/tv/$tmdbId/$season/$episode",
            referer = "https://embed.su"
        )?.let { r ->
            findVideoUrl(r)?.let { Log.d(TAG,"✅ embed.su: $it"); return@withContext it }
        }

        // FlixEmbed
        fetchJson(
            if (isMovie) "https://flixembed.net/api/movie/$tmdbId"
            else         "https://flixembed.net/api/tv/$tmdbId/$season/$episode",
            referer = "https://flixembed.net"
        )?.let { r ->
            findVideoUrl(r)?.let { Log.d(TAG,"✅ FlixEmbed: $it"); return@withContext it }
        }

        // EmbedMe
        fetchJson(
            if (isMovie) "https://embedme.top/api/v2/movie/$tmdbId"
            else         "https://embedme.top/api/v2/tv/$tmdbId/$season/$episode",
            referer = "https://embedme.top"
        )?.let { r ->
            findVideoUrl(r)?.let { Log.d(TAG,"✅ EmbedMe: $it"); return@withContext it }
        }

        Log.d(TAG, "❌ extractDirect: no provider returned a stream")
        null
    }

    // ── PUBLIC: scrape a specific embed URL — used as fallback per-server ──────

    suspend fun extract(
        embedUrl: String,
        tmdbId: Int = 0,
        contentType: String = "movie",
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        try {
            val host = Uri.parse(embedUrl).host?.lowercase() ?: ""
            Log.d(TAG, "extract (scrape fallback): $embedUrl")

            val result = when {
                host.contains("vidsrc.me") || host.contains("vsembed") -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.io")   -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.pm")   -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.to")   -> extractVidSrcTo(embedUrl)
                host.contains("vidsrc.dev")  -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.in")   -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.nl")   -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.su")   -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.lol")  -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc2")     -> extractVidSrcFamily(embedUrl)
                host.contains("vidlink")     -> extractVidLink(embedUrl, tmdbId, contentType, season, episode)
                host.contains("videasy")     -> extractVideasy(embedUrl, tmdbId, contentType, season, episode)
                host.contains("autoembed")   -> extractAutoEmbed(embedUrl, tmdbId, contentType, season, episode)
                else                         -> scrapeUrl(embedUrl)
            }

            if (result != null) Log.d(TAG, "✅ scrape hit: $result")
            else Log.d(TAG, "❌ scrape miss: $embedUrl")
            result
        } catch (e: Exception) {
            Log.e(TAG, "extract() error: ${e.message}")
            null
        }
    }

    // ── Server-specific scrapers ───────────────────────────────────────────────

    private fun extractVidSrcFamily(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }
        tryBase64Urls(html)?.let { return it }

        val dataI = Regex("""data-i\s*=\s*["']?(\w+)""").find(html)?.groupValues?.get(1)
            ?: return followIframes(html, pageUrl)

        val origin = pageUrl.toOrigin()
        for (path in listOf(
            "$origin/ajax/embed/episode?id=$dataI",
            "$origin/ajax/embed/movie?id=$dataI",
            "$origin/ajax/v2/embed/episode?id=$dataI",
            "$origin/ajax/v2/embed/movie?id=$dataI",
            "$origin/ajax/sources/$dataI",
            "$origin/api/source/$dataI",
            "$origin/api/v2/source/$dataI",
            "$origin/api/v3/source/$dataI",
            "https://vsembed.ru/ajax/embed/episode?id=$dataI",
            "https://vsembed.ru/ajax/embed/movie?id=$dataI",
            "https://vidsrc.me/ajax/embed/episode?id=$dataI",
            "https://vidsrc.me/ajax/embed/movie?id=$dataI",
        )) {
            val resp = fetchJson(path, referer = pageUrl) ?: continue
            findVideoUrl(resp)?.let { return it }
            tryBase64Urls(resp)?.let { return it }
        }
        return followIframes(html, pageUrl)
    }

    private fun extractVidSrcTo(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }

        val dataI = Regex("""data-i\s*=\s*["']?(\w+)""").find(html)?.groupValues?.get(1)
        if (dataI != null) {
            val origin = pageUrl.toOrigin()
            for (path in listOf(
                "$origin/ajax/embed/episode?id=$dataI",
                "$origin/ajax/embed/movie?id=$dataI",
                "$origin/api/source/$dataI",
                "https://vsembed.ru/ajax/embed/episode?id=$dataI",
                "https://vsembed.ru/ajax/embed/movie?id=$dataI",
            )) {
                fetchJson(path, referer = pageUrl)?.let { findVideoUrl(it)?.let { u -> return u } }
            }
        }
        return followIframes(html, pageUrl)
    }

    private fun extractVidLink(pageUrl: String, tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        val isMovie = contentType == "movie"
        val api = if (isMovie) "https://vidlink.pro/api/b/movie/$tmdbId"
                  else         "https://vidlink.pro/api/b/tv/$tmdbId/$season/$episode"
        fetchJson(api, referer = pageUrl)?.let { r ->
            Regex(""""playlist"\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(r)?.groupValues?.get(1)?.let { return it }
            findVideoUrl(r)?.let { return it }
        }
        return scrapeUrl(pageUrl)
    }

    private fun extractVideasy(pageUrl: String, tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        val isMovie = contentType == "movie"
        val api = if (isMovie) "https://player.videasy.net/api/movie/$tmdbId"
                  else         "https://player.videasy.net/api/tv/$tmdbId/$season/$episode"
        fetchJson(api, referer = pageUrl)?.let { r ->
            findVideoUrl(r)?.let { return it }
        }
        return scrapeUrl(pageUrl)
    }

    private fun extractAutoEmbed(pageUrl: String, tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        val isMovie = contentType == "movie"
        for (base in listOf("https://autoembed.cc","https://autoembed.co")) {
            fetchJson(
                if (isMovie) "$base/api/v2/movie/$tmdbId" else "$base/api/v2/tv/$tmdbId/$season/$episode",
                referer = pageUrl
            )?.let { findVideoUrl(it)?.let { u -> return u } }
        }
        return scrapeUrl(pageUrl)
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
        Regex("""atob\(\s*["']([A-Za-z0-9+/=]{20,})["']\s*\)""").findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (isValidVideo(decoded)) return decoded
            } catch (_: Exception) { }
        }
        Regex("""["']([A-Za-z0-9+/]{60,}={0,2})["']""").findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (decoded.startsWith("http") && isValidVideo(decoded)) return decoded
            } catch (_: Exception) { }
        }
        return null
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    // NOTE: No Sec-Fetch-* headers — they trigger Cloudflare bot detection
    private fun fetch(url: String, referer: String? = null): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer ?: url)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
            .header("Accept-Language", "en-US,en;q=0.9")
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

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun isValidVideo(url: String): Boolean {
        if (url.isBlank() || url.length < 20) return false
        val lower = url.lowercase()
        return (lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains("/hls/")  || lower.contains("/stream/")) && !isAdUrl(url)
    }

    private fun isAdUrl(url: String): Boolean {
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        return adDomains.any { host.contains(it) }
    }

    private fun String.toOrigin(): String = try {
        val u = Uri.parse(this); "${u.scheme}://${u.host}"
    } catch (_: Exception) { this }
}
