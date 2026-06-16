package com.mariocart.app.data.server

import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object StreamExtractor {

    private const val TAG = "StreamExtractor"
    private const val TIMEOUT_MS = 20_000L

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
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""playlist"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex(""""stream"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""", RegexOption.IGNORE_CASE),
        Regex("""hls\.loadSource\(\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex(""""sources"\s*:\s*\[\s*\{\s*"src"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""", RegexOption.IGNORE_CASE),
        Regex("""data-(?:src|file|url|hls)\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)""", RegexOption.IGNORE_CASE),
        Regex("""jwplayer\s*\([^)]+\)\s*\.setup\s*\([^)]*"file"\s*:\s*"(https?://[^"]+)""", RegexOption.IGNORE_CASE),
        Regex("""Playerjs\(\{[^}]*?file\s*:\s*["']?(https?://[^"'\s,}\]]+)""", RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.m3u8(?:\?[^\s"'<>()\]]*)?)""", RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.mp4(?:\?[^\s"'<>()\]]*)?)""", RegexOption.IGNORE_CASE),
        Regex("""["'](https?://[^"'\s]+/(?:master|index|playlist)\.m3u8[^"'\s]*)["']""", RegexOption.IGNORE_CASE),
    )

    suspend fun extract(
        embedUrl: String,
        tmdbId: Int = 0,
        contentType: String = "movie",
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        try {
            val host = Uri.parse(embedUrl).host?.lowercase() ?: ""
            Log.d(TAG, "Extracting: $embedUrl  tmdbId=$tmdbId")

            if (tmdbId > 0) {
                val direct = tryDirectApis(host, tmdbId, contentType, season, episode)
                if (direct != null) {
                    Log.d(TAG, "✅ Direct API hit: $direct")
                    return@withContext direct
                }
            }

            val result = when {
                host.contains("vidsrc.me")  || host.contains("vsembed")  -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.io")                                -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.pm")                                -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.to")                                -> extractVidSrcTo(embedUrl)
                host.contains("vidsrc.dev")                               -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.in")                                -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.nl")                                -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.su")                                -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc.lol")                               -> extractVidSrcFamily(embedUrl)
                host.contains("vidsrc2")                                  -> extractVidSrcFamily(embedUrl)
                host.contains("vidlink")                                  -> extractVidLink(embedUrl, tmdbId, contentType, season, episode)
                host.contains("videasy")                                  -> extractVideasy(embedUrl, tmdbId, contentType, season, episode)
                host.contains("autoembed")                                -> extractAutoEmbed(embedUrl, tmdbId, contentType, season, episode)
                host.contains("2embed")                                   -> extractGenericWithApi(embedUrl)
                host.contains("embedrise")                                -> scrapeUrl(embedUrl)
                host.contains("moviesapi")                                -> extractMoviesApi(embedUrl, tmdbId, contentType, season, episode)
                host.contains("superembed")                               -> extractSuperEmbed(embedUrl, tmdbId, contentType, season, episode)
                host.contains("vidbinge")                                 -> extractVidBinge(embedUrl, tmdbId, contentType, season, episode)
                host.contains("embed.su")                                 -> extractEmbedSu(embedUrl, tmdbId, contentType, season, episode)
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

    private fun tryDirectApis(
        host: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ): String? {
        val isMovie = contentType == "movie"

        if (host.contains("vidlink")) {
            val url = if (isMovie) "https://vidlink.pro/api/b/movie/$tmdbId"
                      else "https://vidlink.pro/api/b/tv/$tmdbId/$season/$episode"
            fetchJson(url)?.let { resp ->
                findVideoUrl(resp)?.let { return it }
            }
        }

        if (host.contains("videasy")) {
            val url = if (isMovie) "https://player.videasy.net/api/movie/$tmdbId"
                      else "https://player.videasy.net/api/tv/$tmdbId/$season/$episode"
            fetchJson(url)?.let { resp ->
                findVideoUrl(resp)?.let { return it }
                Regex(""""(?:hls|stream|playlist|url)"\s*:\s*"(https?://[^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(resp)?.groupValues?.get(1)?.let { if (isValidVideo(it)) return it }
            }
        }

        if (host.contains("autoembed")) {
            for (base in listOf("https://autoembed.cc", "https://autoembed.co")) {
                val path = if (isMovie) "$base/api/v2/movie/$tmdbId"
                           else "$base/api/v2/tv/$tmdbId/$season/$episode"
                fetchJson(path)?.let { resp -> findVideoUrl(resp)?.let { return it } }
            }
        }

        if (host.contains("superembed")) {
            val url = if (isMovie) "https://superembed.stream/api/v2/movie/$tmdbId"
                      else "https://superembed.stream/api/v2/tv/$tmdbId/$season/$episode"
            fetchJson(url)?.let { resp -> findVideoUrl(resp)?.let { return it } }
        }

        if (host.contains("vidbinge")) {
            val url = if (isMovie) "https://vidbinge.dev/api/v2/movie?id=$tmdbId"
                      else "https://vidbinge.dev/api/v2/tv?id=$tmdbId&s=$season&e=$episode"
            fetchJson(url)?.let { resp -> findVideoUrl(resp)?.let { return it } }
        }

        if (host.contains("moviesapi")) {
            val url = if (isMovie) "https://moviesapi.club/api/v2/movie/$tmdbId"
                      else "https://moviesapi.club/api/v2/tv/$tmdbId/$season/$episode"
            fetchJson(url)?.let { resp -> findVideoUrl(resp)?.let { return it } }
        }

        if (host.contains("embed.su")) {
            val url = if (isMovie) "https://embed.su/api/source/$tmdbId"
                      else "https://embed.su/api/source/tv/$tmdbId/$season/$episode"
            fetchJson(url)?.let { resp -> findVideoUrl(resp)?.let { return it } }
        }

        if (host.contains("flixembed")) {
            val url = if (isMovie) "https://flixembed.net/api/movie/$tmdbId"
                      else "https://flixembed.net/api/tv/$tmdbId/$season/$episode"
            fetchJson(url)?.let { resp -> findVideoUrl(resp)?.let { return it } }
        }

        if (host.contains("embedme")) {
            val url = if (isMovie) "https://embedme.top/api/v2/movie/$tmdbId"
                      else "https://embedme.top/api/v2/tv/$tmdbId/$season/$episode"
            fetchJson(url)?.let { resp -> findVideoUrl(resp)?.let { return it } }
        }

        return null
    }

    private fun extractVidSrcFamily(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }
        tryBase64Urls(html)?.let { return it }

        val dataI = Regex("""data-i\s*=\s*["']?(\w+)""").find(html)?.groupValues?.get(1)
            ?: return followIframes(html, pageUrl)

        val origin = pageUrl.toOrigin()
        val ajaxPaths = listOf(
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
        )
        for (path in ajaxPaths) {
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
            val ajaxPaths = listOf(
                "$origin/ajax/embed/episode?id=$dataI",
                "$origin/ajax/embed/movie?id=$dataI",
                "$origin/api/source/$dataI",
                "https://vsembed.ru/ajax/embed/episode?id=$dataI",
                "https://vsembed.ru/ajax/embed/movie?id=$dataI",
                "https://vidsrc.me/ajax/embed/episode?id=$dataI",
            )
            for (path in ajaxPaths) {
                val resp = fetchJson(path, referer = pageUrl) ?: continue
                findVideoUrl(resp)?.let { return it }
            }
        }
        return followIframes(html, pageUrl)
    }

    private fun extractVidLink(
        pageUrl: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ): String? {
        val isMovie = contentType == "movie"
        val apiUrl = if (isMovie) "https://vidlink.pro/api/b/movie/$tmdbId"
                     else "https://vidlink.pro/api/b/tv/$tmdbId/$season/$episode"
        fetchJson(apiUrl, referer = pageUrl)?.let { resp ->
            Regex(""""playlist"\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                .find(resp)?.groupValues?.get(1)?.let { return it }
            findVideoUrl(resp)?.let { return it }
        }

        val segs = Uri.parse(pageUrl).pathSegments
        val fallbackUrl = when {
            segs.size >= 2 && segs[0] == "movie" -> "https://vidlink.pro/api/b/movie/${segs[1]}"
            segs.size >= 4 && segs[0] == "tv"    -> "https://vidlink.pro/api/b/tv/${segs[1]}/${segs[2]}/${segs[3]}"
            segs.size >= 3 && segs[1] == "movie" -> "https://vidlink.pro/api/b/movie/${segs[2]}"
            segs.size >= 5 && segs[1] == "tv"    -> "https://vidlink.pro/api/b/tv/${segs[2]}/${segs[3]}/${segs[4]}"
            else -> null
        }
        if (fallbackUrl != null && fallbackUrl != apiUrl) {
            fetchJson(fallbackUrl, referer = pageUrl)?.let { resp ->
                findVideoUrl(resp)?.let { return it }
            }
        }
        return scrapeUrl(pageUrl)
    }

    private fun extractVideasy(
        pageUrl: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ): String? {
        val isMovie = contentType == "movie"
        val apiUrl = if (isMovie) "https://player.videasy.net/api/movie/$tmdbId"
                     else "https://player.videasy.net/api/tv/$tmdbId/$season/$episode"
        fetchJson(apiUrl, referer = pageUrl)?.let { resp ->
            findVideoUrl(resp)?.let { return it }
            Regex(""""(?:hls|stream|playlist|url)"\s*:\s*"(https?://[^"]+)"""", RegexOption.IGNORE_CASE)
                .find(resp)?.groupValues?.get(1)?.let { if (isValidVideo(it)) return it }
        }
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }
        return followIframes(html, pageUrl)
    }

    private fun extractAutoEmbed(
        pageUrl: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ): String? {
        val isMovie = contentType == "movie"
        for (base in listOf("https://autoembed.cc", "https://autoembed.co")) {
            val path = if (isMovie) "$base/api/v2/movie/$tmdbId"
                       else "$base/api/v2/tv/$tmdbId/$season/$episode"
            fetchJson(path, referer = pageUrl)?.let { resp ->
                findVideoUrl(resp)?.let { return it }
            }
        }
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
    }

    private fun extractMoviesApi(
        pageUrl: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ): String? {
        val isMovie = contentType == "movie"
        val path = if (isMovie) "https://moviesapi.club/api/v2/movie/$tmdbId"
                   else "https://moviesapi.club/api/v2/tv/$tmdbId/$season/$episode"
        fetchJson(path, referer = pageUrl)?.let { resp ->
            findVideoUrl(resp)?.let { return it }
        }
        return scrapeUrl(pageUrl)
    }

    private fun extractSuperEmbed(
        pageUrl: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ): String? {
        val isMovie = contentType == "movie"
        val url = if (isMovie) "https://superembed.stream/api/v2/movie/$tmdbId"
                  else "https://superembed.stream/api/v2/tv/$tmdbId/$season/$episode"
        fetchJson(url, referer = pageUrl)?.let { resp ->
            findVideoUrl(resp)?.let { return it }
        }
        return scrapeUrl(pageUrl)
    }

    private fun extractVidBinge(
        pageUrl: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ): String? {
        val isMovie = contentType == "movie"
        val url = if (isMovie) "https://vidbinge.dev/api/v2/movie?id=$tmdbId"
                  else "https://vidbinge.dev/api/v2/tv?id=$tmdbId&s=$season&e=$episode"
        fetchJson(url, referer = pageUrl)?.let { resp ->
            findVideoUrl(resp)?.let { return it }
        }
        return scrapeUrl(pageUrl)
    }

    private fun extractEmbedSu(
        pageUrl: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ): String? {
        val isMovie = contentType == "movie"
        val url = if (isMovie) "https://embed.su/api/source/$tmdbId"
                  else "https://embed.su/api/source/tv/$tmdbId/$season/$episode"
        fetchJson(url, referer = pageUrl)?.let { resp ->
            findVideoUrl(resp)?.let { return it }
        }
        return scrapeUrl(pageUrl)
    }

    private fun extractGenericWithApi(pageUrl: String): String? {
        val html = fetch(pageUrl) ?: return null
        findVideoUrl(html)?.let { return it }
        tryBase64Urls(html)?.let { return it }
        return followIframes(html, pageUrl)
    }

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
