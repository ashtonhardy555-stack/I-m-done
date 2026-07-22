package com.freeflix.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * VidSrcMeResolver — resolves **direct playable** stream URLs from the
 * sub-servers embedded inside vidsrc.me embed pages.
 *
 * ## How it works (based on the Ciarands/vidsrc-me-resolver approach)
 *
 *  1. `GET https://vidsrc.me/embed/{movie|tv}?tmdb={id}[&season={s}&episode={e}]`
 *     → HTML containing `<div class="server" data-hash="...">` elements.
 *     Each represents a sub-server (VidSrc PRO, SuperEmbed, etc.).
 *
 *  2. For each sub-server hash: `GET https://vidsrc.stream/rcp/{hash}`
 *     → HTML with `<div id="hidden" data-h="{hex}">` and `<body data-i="{seed}">`.
 *     The source URL is XOR-decoded: `decode(hex, seed)` where seed = imdb id.
 *
 *  3. The decoded URL redirects to either:
 *     - `vidsrc.stream` → VidSrc PRO player → extract `file:"..."` (base64-encoded HLS)
 *     - `multiembed.mov` → SuperEmbed player → eval/hunter unpacked HLS URLs
 *
 *  4. VidSrc PRO HLS is base64-encoded with a custom format: strip leading
 *     2 chars, remove `@#@` segments, base64url-decode → real m3u8 URL.
 *
 *  5. SuperEmbed uses an obfuscated `eval(function(h,u,n,t,e,r){...})` hunter
 *     unpacker. We try to regex out `file:"..."` URLs from the page directly
 *     as a simpler approach that works in most cases.
 *
 * This extractor tries EVERY sub-server and returns the first verified
 * playable stream, maximising coverage across different content.
 */
object VidSrcMeResolver {

    private const val TAG = "VidSrcMeResolver"

    private const val EMBED_BASE = "https://vidsrc.me/embed"
    private const val RCP_BASE = "https://vidsrc.stream/rcp"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val noRedirectClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val verifier by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    sealed class Result {
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidSrcMe"
        ) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): Result = withContext(Dispatchers.IO) {
        val kind = if (contentType == "tv") "tv" else "movie"
        val embedUrl = if (contentType == "tv") {
            "$EMBED_BASE/$kind?tmdb=$tmdbId&season=$season&episode=$episode"
        } else {
            "$EMBED_BASE/$kind?tmdb=$tmdbId"
        }

        Log.d(TAG, "VidSrcMe embed: $embedUrl")

        // Step 1: Fetch the embed page and extract all sub-server hashes.
        val embedHtml = fetchText(embedUrl, null) ?: run {
            Log.w(TAG, "Failed to fetch embed page")
            return@withContext Result.Error("VidSrcMe: embed page unreachable")
        }

        val servers = extractServerHashes(embedHtml)
        if (servers.isEmpty()) {
            Log.w(TAG, "No sub-servers found on embed page")
            return@withContext Result.Error("VidSrcMe: no sub-servers available")
        }

        Log.d(TAG, "Found ${servers.size} sub-servers: ${servers.keys}")

        val referer = "https://vidsrc.me/"

        // Step 2: Try each sub-server — first verified stream wins.
        for ((serverName, hash) in servers) {
            try {
                Log.d(TAG, "↳ Trying sub-server: $serverName (hash=$hash)")

                // GET rcp/{hash} → get the encoded source
                val rcpUrl = "$RCP_BASE/$hash"
                val rcpHtml = fetchText(rcpUrl, referer) ?: continue

                // Extract data-h (hex encoded) and data-i (seed = imdb id without "tt")
                val dataH = extractAttr(rcpHtml, "id=\"hidden\"", "data-h")
                val dataI = extractAttr(rcpHtml, "<body", "data-i")

                if (dataH == null || dataI == null) {
                    Log.w(TAG, "  No data-h/data-i in RCP response for $serverName")
                    continue
                }

                // Decode the source URL: XOR each byte with the seed char
                val decodedUrl = decodeSrc(dataH, dataI)
                if (decodedUrl.isBlank()) continue

                val fullUrl = if (decodedUrl.startsWith("//")) "https:$decodedUrl" else decodedUrl
                Log.d(TAG, "  Decoded source: $fullUrl")

                // Follow redirect to determine the player type
                val redirectUrl = getRedirect(fullUrl, rcpUrl) ?: fullUrl

                when {
                    redirectUrl.contains("vidsrc.stream") -> {
                        // VidSrc PRO player — extract base64-encoded HLS
                        val streamUrl = resolveVidSrcPro(redirectUrl, rcpUrl)
                        if (streamUrl != null && verifyUrl(streamUrl, rcpUrl)) {
                            Log.i(TAG, "✅ VidSrcMe·$serverName (PRO): $streamUrl")
                            return@withContext Result.Stream(
                                streamUrl,
                                mapOf(
                                    "Referer" to "https://vidsrc.stream/",
                                    "User-Agent" to USER_AGENT
                                ),
                                "VidSrcMe·$serverName"
                            )
                        }
                    }
                    redirectUrl.contains("multiembed.mov") || redirectUrl.contains("multiembed") -> {
                        // SuperEmbed player — try to extract file: URLs
                        val streamUrl = resolveSuperEmbed(redirectUrl, rcpUrl)
                        if (streamUrl != null && verifyUrl(streamUrl, rcpUrl)) {
                            Log.i(TAG, "✅ VidSrcMe·$serverName (SuperEmbed): $streamUrl")
                            return@withContext Result.Stream(
                                streamUrl,
                                mapOf(
                                    "Referer" to "https://multiembed.mov/",
                                    "User-Agent" to USER_AGENT
                                ),
                                "VidSrcMe·$serverName"
                            )
                        }
                    }
                    else -> {
                        // Unknown redirect — try to extract a direct stream
                        val streamUrl = extractDirectStream(redirectUrl, rcpUrl)
                        if (streamUrl != null && verifyUrl(streamUrl, rcpUrl)) {
                            Log.i(TAG, "✅ VidSrcMe·$serverName (direct): $streamUrl")
                            return@withContext Result.Stream(
                                streamUrl,
                                mapOf("Referer" to rcpUrl, "User-Agent" to USER_AGENT),
                                "VidSrcMe·$serverName"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "  Sub-server $serverName failed: ${e.message}")
            }
        }

        Result.Error("VidSrcMe: no playable stream from any sub-server")
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Sub-server resolution                                                     //
    // ───────────────────────────────────────────────────────────────────────────

    /** Extract the VidSrc PRO HLS URL (base64-encoded `file:"..."` in the player page). */
    private fun resolveVidSrcPro(pageUrl: String, referer: String): String? {
        val html = fetchText(pageUrl, referer) ?: return null

        // Look for file:"<encoded>" in the page
        val fileRegex = Regex("""file:"([^"]*)"""")
        val encoded = fileRegex.find(html)?.groupValues?.getOrNull(1) ?: return null

        return try {
            decodeHlsUrl(encoded)
        } catch (e: Exception) {
            Log.w(TAG, "VidSrc PRO decode failed: ${e.message}")
            null
        }
    }

    /** Extract stream URLs from a SuperEmbed/multiembed player page. */
    private fun resolveSuperEmbed(pageUrl: String, referer: String): String? {
        val html = fetchText(pageUrl, referer) ?: return null

        // Try direct file:"..." extraction first (often works without full hunter unpacking)
        val fileRegex = Regex("""file:"([^"]*\.m3u8[^"]*)"""")
        val direct = fileRegex.find(html)?.groupValues?.getOrNull(1)
        if (direct != null && direct.startsWith("http")) return direct

        // Try file:"..." for any URL pattern
        val anyFileRegex = Regex("""file:"(https?://[^"]*)"""")
        val anyUrl = anyFileRegex.find(html)?.groupValues?.getOrNull(1)
        if (anyUrl != null && (anyUrl.contains(".m3u8") || anyUrl.contains(".mp4"))) return anyUrl

        // Try sources pattern
        val sourcesRegex = Regex("""sources:\s*\[\{[^}]*file:\s*"(https?://[^"]*)"""")
        val sourcesUrl = sourcesRegex.find(html)?.groupValues?.getOrNull(1)
        if (sourcesUrl != null) return sourcesUrl

        return null
    }

    /** Try to extract a direct m3u8/mp4 URL from an arbitrary page. */
    private fun extractDirectStream(pageUrl: String, referer: String): String? {
        val html = fetchText(pageUrl, referer) ?: return null

        // Common patterns: file:"url", src:"url", source src="url"
        val patterns = listOf(
            Regex("""file:"(https?://[^"]*\.(?:m3u8|mp4)[^"]*)""" ),
            Regex("""src["']?\s*[:=]\s*["'](https?://[^"]*\.(?:m3u8|mp4)[^"']*)"""),
            Regex("""source\s+src=["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)"""),
            Regex(""""(https?://[^"]*\.(?:m3u8|mp4)[^"]*)"""")
        )

        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val url = match.groupValues[1]
                if (url.contains(".m3u8") || url.contains(".mp4")) return url
            }
        }
        return null
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Decoding helpers                                                          //
    // ───────────────────────────────────────────────────────────────────────────

    /** XOR-decode the hex-encoded source URL using the seed (imdb id without "tt"). */
    private fun decodeSrc(hexEncoded: String, seed: String): String {
        val bytes = hexEncoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        for (i in bytes.indices) {
            sb.append(Char((bytes[i].toInt() xor seedBytes[i % seedBytes.size].toInt()).toChar().code))
        }
        return sb.toString()
    }

    /** Decode the VidSrc PRO base64-encoded HLS URL (strip 2 chars, remove @#@, base64url decode). */
    private fun decodeHlsUrl(encoded: String): String {
        var data = encoded
        // Strip leading 2 characters
        if (data.length > 2) data = data.substring(2)

        // Remove /@#@/...== patterns
        val atAtRegex = Regex("""/@#@/[^=/]+==""")
        while (atAtRegex.containsMatchIn(data)) {
            data = atAtRegex.replace(data, "")
        }

        // Base64 URL-safe decode
        val standardized = data.replace('_', '/').replace('-', '+')
        // Add padding
        val padded = when (standardized.length % 4) {
            2 -> "$standardized=="
            3 -> "$standardized="
            else -> standardized
        }
        val decoded = java.util.Base64.getDecoder().decode(padded)
        return String(decoded, Charsets.UTF_8)
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  HTML parsing helpers                                                      //
    // ───────────────────────────────────────────────────────────────────────────

    /** Extract all sub-server name → hash pairs from the embed page. */
    private fun extractServerHashes(html: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        // Pattern: <div class="server" data-hash="abc123">ServerName</div>
        // Also matches: <div ... class="...server..." ... data-hash="...">
        val serverRegex = Regex(
            """<div[^>]*class="[^"]*server[^"]*"[^>]*data-hash="([^"]+)"[^>]*>\s*([^<]+)""",
            RegexOption.IGNORE_CASE
        )
        for (match in serverRegex.findAll(html)) {
            val hash = match.groupValues[1]
            val name = match.groupValues[2].trim()
            if (hash.isNotBlank() && name.isNotBlank()) {
                result[name] = hash
            }
        }

        // Fallback: also look for data-hash without text (use index as name)
        if (result.isEmpty()) {
            val hashRegex = Regex("""data-hash="([^"]+)"""")
            var idx = 1
            for (match in hashRegex.findAll(html)) {
                result["Server$idx"] = match.groupValues[1]
                idx++
            }
        }

        return result
    }

    /** Extract an attribute value from an HTML element identified by a tag fragment. */
    private fun extractAttr(html: String, tagFragment: String, attrName: String): String? {
        val idx = html.indexOf(tagFragment)
        if (idx < 0) return null
        // Find the end of this tag (next '>')
        val tagEnd = html.indexOf('>', idx)
        if (tagEnd < 0) return null
        val tag = html.substring(idx, tagEnd)

        val attrRegex = Regex("""$attrName="([^"]+)"""")
        return attrRegex.find(tag)?.groupValues?.getOrNull(1)
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  HTTP helpers                                                              //
    // ───────────────────────────────────────────────────────────────────────────

    private fun fetchText(url: String, referer: String?): String? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml,*/*;q=0.8")
            if (referer != null) builder.header("Referer", referer)
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 200) {
                    resp.body?.string()
                } else {
                    Log.d(TAG, "HTTP ${resp.code} for $url")
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchText error: ${e.message}")
            null
        }
    }

    private fun getRedirect(url: String, referer: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .build()
            noRedirectClient.newCall(req).execute().use { resp ->
                if (resp.code in 301..308) {
                    resp.header("Location")
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun verifyUrl(url: String, referer: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Range", "bytes=0-1")
                .build()
            verifier.newCall(req).execute().use { resp ->
                val code = resp.code
                val ct = resp.header("Content-Type") ?: ""
                // Accept 200, 206, 416 (range), 403 (may still play with headers)
                val ok = code in 200..299 || code == 416 || code == 403
                Log.d(TAG, "verifyUrl($url): code=$code ct=$ct ok=$ok")
                ok
            }
        } catch (e: Exception) {
            // Network error — trust the extraction if the URL looks valid
            url.contains(".m3u8") || url.contains(".mp4")
        }
    }
}
