package com.mariocart.app.data.server

import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

object StreamExtractor {

    private const val TAG = "StreamExtractor"
    private const val TIMEOUT_S = 15L

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val cookieJar = JavaNetCookieJar(
        CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    private val videoPatterns = listOf(
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",    RegexOption.IGNORE_CASE),
        Regex(""""file"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""",     RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",     RegexOption.IGNORE_CASE),
        Regex(""""src"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""",      RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.m3u8[^"'\s,}\]]*)""",     RegexOption.IGNORE_CASE),
        Regex(""""url"\s*:\s*["']?(https?://[^"'\s,}\]]+\.mp4[^"'\s,}\]]*)""",      RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.m3u8(?:\?[^\s"'<>()\]]*)?)""",           RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+\.mp4(?:\?[^\s"'<>()\]]*)?)""",            RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+playlist\.m3u8(?:\?[^\s"'<>()\]]*)?)""",   RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+/hls/[^\s"'<>()\]]*)""",                   RegexOption.IGNORE_CASE),
        Regex("""(https?://[^\s"'<>()\]]+/proxy/[^\s"'<>()\]]*playlist\.m3u8[^\s"'<>()\]]*)""", RegexOption.IGNORE_CASE),
    )

    suspend fun extractDirect(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        val isMovie = contentType == "movie"
        Log.d(TAG, "extractDirect id=$tmdbId type=$contentType s=$season e=$episode")

        val tasks = listOf(
            async { extractVidLink(tmdbId, contentType, season, episode) },
            async { extractVidsrcPro(tmdbId, contentType, season, episode) },
            async { extractVideasy(tmdbId, contentType, season, episode) },
            async { extractAutoEmbed(tmdbId, contentType, season, episode) },
            async { extractEmbedSu(tmdbId, contentType, season, episode) }
        )

        tasks.awaitAll().firstOrNull { it != null }
    }

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
                host.contains("vidlink")     -> extractVidLink(tmdbId, contentType, season, episode)
                host.contains("vidsrc.pro")  -> extractVidsrcPro(tmdbId, contentType, season, episode)
                host.contains("videasy")     -> extractVideasy(tmdbId, contentType, season, episode)
                host.contains("autoembed")   -> extractAutoEmbed(tmdbId, contentType, season, episode)
                host.contains("embed.su")    -> extractEmbedSu(tmdbId, contentType, season, episode)
                else                         -> scrapeUrl(embedUrl)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "extract() error: ${e.message}")
            null
        }
    }

    private fun extractVidLink(tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        val isMovie = contentType == "movie"
        val api = if (isMovie) "https://vidlink.pro/api/b/movie/$tmdbId"
                  else         "https://vidlink.pro/api/b/tv/$tmdbId/$season/$episode"
        return fetchJson(api, referer = "https://vidlink.pro")?.let { findVideoUrl(it) }
    }

    private fun extractVidsrcPro(tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        val isMovie = contentType == "movie"
        val api = if (isMovie) "https://vidsrc.pro/api/source/movie/$tmdbId"
                  else         "https://vidsrc.pro/api/source/tv/$tmdbId/$season/$episode"
        return fetchJson(api, referer = "https://vidsrc.pro")?.let { findVideoUrl(it) }
    }

    private fun extractVideasy(tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        val isMovie = contentType == "movie"
        val api = if (isMovie) "https://player.videasy.net/api/movie/$tmdbId"
                  else         "https://player.videasy.net/api/tv/$tmdbId/$season/$episode"
        return fetchJson(api, referer = "https://player.videasy.net")?.let { findVideoUrl(it) }
    }

    private fun extractAutoEmbed(tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        val isMovie = contentType == "movie"
        val api = if (isMovie) "https://autoembed.cc/api/v2/movie/$tmdbId"
                  else         "https://autoembed.cc/api/v2/tv/$tmdbId/$season/$episode"
        return fetchJson(api, referer = "https://autoembed.cc")?.let { findVideoUrl(it) }
    }

    private fun extractEmbedSu(tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        val isMovie = contentType == "movie"
        val api = if (isMovie) "https://embed.su/api/source/$tmdbId"
                  else         "https://embed.su/api/source/tv/$tmdbId/$season/$episode"
        return fetchJson(api, referer = "https://embed.su")?.let { findVideoUrl(it) }
    }

    private fun scrapeUrl(url: String): String? {
        val html = fetch(url) ?: return null
        return findVideoUrl(html)
    }

    private fun findVideoUrl(html: String): String? {
        for (pattern in videoPatterns) {
            pattern.find(html)?.groupValues?.get(1)?.let {
                if (isValidVideo(it)) return it
            }
        }
        return null
    }

    private fun fetch(url: String, referer: String? = null): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer ?: url)
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
            .header("Accept", "application/json")
            .build()
        val resp = client.newCall(req).execute()
        val body = if (resp.isSuccessful) resp.body?.string() else null
        resp.close()
        body
    } catch (_: Exception) { null }

    private fun isValidVideo(url: String): Boolean {
        if (url.isBlank() || url.length < 20) return false
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }
}
