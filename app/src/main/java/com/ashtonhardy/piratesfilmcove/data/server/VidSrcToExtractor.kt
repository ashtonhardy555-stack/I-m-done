package com.ashtonhardy.piratesfilmcove.data.server

import android.util.Log
import com.ashtonhardy.piratesfilmcove.BuildConfig
import com.ashtonhardy.piratesfilmcove.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * VidSrcToExtractor — a pure-OkHttp, **no-WebView, no-Kodi** headless
 * extractor for **vidsrc.to**, ported from the `cool-dev-guy/vidsrc-api`
 * project (which itself is based on the Ciarands vidsrc resolver).
 *
 * ## Why this exists
 *
 * The app already has a `VidSrcMeResolver` (for vidsrc.me) and a
 * `VidSrcExtractor`, but the **vidsrc.to** flow is a different beast: it
 * uses a two-step AJAX API with **RC4 decryption** to reveal the upstream
 * embed URL (VidPlay or FileMoon), which is then resolved to a direct
 * `.m3u8` stream. This extractor runs that entire flow headlessly — no
 * WebView, no JS engine, no Kodi runtime — pure OkHttp, the same approach
 * as `LookMovieHeadlessExtractor`.
 *
 * It is registered as a KodiEngine addon so the engine can use it as
 * another racer for the parallel resolve lane, covering titles the other
 * addons miss.
 *
 * ## The flow (ported from models/vidsrcto.py)
 *
 * 1. **TMDB → IMDb** — resolve the IMDb id (`ttXXXXXXX`) from the TMDB id
 *    via TMDB's `external_ids` endpoint (same as SmashStreamsExtractor).
 *
 * 2. **EMBED PAGE** — `GET https://vidsrc.to/embed/{media}/{imdbId}`
 *    (TV: `/embed/tv/{imdbId}/{season}/{episode}`). Parse the HTML for the
 *    first `<a data-id="...">` element to get the `sources_code`.
 *
 * 3. **SOURCES** — `GET https://vidsrc.to/ajax/embed/episode/{sources_code}/sources`
 *    → JSON `{ "result": [{ "id": "...", "title": "VidPlay"|"FileMoon",
 *    "encryptedUrl": "..." }] }`.
 *
 * 4. **DECRYPT** — Each source's `encryptedUrl` is RC4-encrypted with the
 *    key `WXrUARXb1aDLaZjI` (base64url-encoded). We decrypt it to get the
 *    upstream VidPlay/FileMoon URL.
 *
 * 5. **RESOLVE UPSTREAM** — For VidPlay URLs we run the VidPlay decoder
 *    (RC4 with Ciarands keys + futoken + mediainfo). For FileMoon we
 *    unpack the packed JS to find the HLS `file:` URL.
 *
 * This extractor is a best-effort racer — if any step fails it returns
 * `Result.Error` and the engine moves to the next addon.
 */
object VidSrcToExtractor {

    private const val TAG = "VidSrcTo"

    private const val VIDSRC_TO_BASE = "https://vidsrc.to"
    private const val VIDSRC_KEY = "WXrUARXb1aDLaZjI"

    // Ciarands vidsrc keys (rotated periodically; fetched live for resilience).
    private const val CIARANDS_KEYS_URL =
        "https://raw.githubusercontent.com/Ciarands/vidsrc-keys/main/keys.json"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    sealed class Result {
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidSrc.to"
        ) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Resolve a direct playable stream for the given TMDB content.
     *
     * @param tmdbId      TMDB id of the movie/show
     * @param contentType "movie" or "tv"
     * @param season      1-indexed season (TV only)
     * @param episode     1-indexed episode (TV only)
     */
    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): Result = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext Result.Error("VidSrc.to: no tmdbId")

        val isTv = contentType.equals("tv", ignoreCase = true)
        try {
            // ── 1. TMDB → IMDb ──
            val imdbId = resolveImdbId(tmdbId, isTv)
            if (imdbId.isNullOrBlank()) {
                return@withContext Result.Error("VidSrc.to: no IMDb id for TMDB $tmdbId")
            }
            Log.d(TAG, "🔍 TMDB $tmdbId → IMDb $imdbId")

            // ── 2. EMBED PAGE ──
            val embedUrl = if (isTv) {
                "$VIDSRC_TO_BASE/embed/tv/$imdbId/$season/$episode"
            } else {
                "$VIDSRC_TO_BASE/embed/movie/$imdbId"
            }
            val embedHtml = fetch(embedUrl) ?: return@withContext Result.Error("VidSrc.to: embed page fetch failed")

            // Parse for the first <a data-id="..."> element
            val dataIdRegex = Regex("""data-id\s*=\s*["']([a-zA-Z0-9]+)["']""")
            val sourcesCode = dataIdRegex.find(embedHtml)?.value?.let {
                Regex("""["']([a-zA-Z0-9]+)["']""").find(it)?.groupValues?.get(1)
            } ?: return@withContext Result.Error("VidSrc.to: no data-id in embed page")

            Log.d(TAG, "📋 sources_code: $sourcesCode")

            // ── 3. SOURCES ──
            val sourcesUrl = "$VIDSRC_TO_BASE/ajax/embed/episode/$sourcesCode/sources"
            val sourcesJson = fetch(sourcesUrl) ?: return@withContext Result.Error("VidSrc.to: sources fetch failed")
            val sourcesArr = try {
                JSONObject(sourcesJson).optJSONArray("result")
            } catch (e: Exception) {
                return@withContext Result.Error("VidSrc.to: sources JSON parse failed: ${e.message}")
            }
            if (sourcesArr == null || sourcesArr.length() == 0) {
                return@withContext Result.Error("VidSrc.to: no sources returned")
            }

            // ── 4. DECRYPT each source and try to resolve ──
            for (i in 0 until sourcesArr.length()) {
                val src = sourcesArr.optJSONObject(i) ?: continue
                val title = src.optString("title", "")
                val encryptedUrl = src.optString("url", src.optString("encryptedUrl", ""))
                if (encryptedUrl.isBlank()) continue

                val decryptedUrl = try {
                    rc4DecryptBase64Url(encryptedUrl, VIDSRC_KEY)
                } catch (e: Exception) {
                    Log.d(TAG, "decrypt failed for source $i ($title): ${e.message}")
                    continue
                }
                Log.d(TAG, "🔓 source[$i] $title → $decryptedUrl")

                // ── 5. RESOLVE UPSTREAM ──
                val streamResult = when {
                    title.equals("VidPlay", ignoreCase = true) || decryptedUrl.contains("vidplay.online") ->
                        resolveVidPlay(decryptedUrl)
                    title.equals("FileMoon", ignoreCase = true) || decryptedUrl.contains("filemoon") ->
                        resolveFileMoon(decryptedUrl)
                    decryptedUrl.contains(".m3u8") -> {
                        // Direct HLS — return as-is.
                        Result.Stream(decryptedUrl, defaultHeaders(), "VidSrc.to")
                    }
                    else -> {
                        // Try vidplay as a fallback for unknown embed types.
                        resolveVidPlay(decryptedUrl)
                    }
                }
                if (streamResult is Result.Stream) return@withContext streamResult
            }

            Result.Error("VidSrc.to: all sources failed to resolve")
        } catch (e: Exception) {
            Result.Error("VidSrc.to: ${e.message}")
        }
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  VidPlay resolver                                                      //
    // ───────────────────────────────────────────────────────────────────────//

    /**
     * Resolve a VidPlay embed URL to a direct `.m3u8` stream.
     *
     * Flow (ported from models/vidplay.py):
     * 1. Split the URL into the base path (`/e/{id}`) and the query string.
     * 2. RC4-decrypt the id with Ciarands key1, then RC4-decrypt the result
     *    with key2, base64-encode → get the `key` for the mediainfo call.
     * 3. Fetch `https://vidplay.online/futoken` → extract `k` variable.
     * 4. Build the `data` param: `"{fuKey},{ord(fuKey[i%len]) + ord(key[i]) for i}"`
     * 5. `GET https://vidplay.online/mediainfo/{data}?{query}&autostart=true`
     *    → JSON `{ "result": { "sources": [{ "file": "https://...m3u8" }] } }`
     */
    private suspend fun resolveVidPlay(embedUrl: String): Result {
        return try {
            val questionIdx = embedUrl.indexOf('?')
            val srcUrl = if (questionIdx >= 0) embedUrl.substring(0, questionIdx) else embedUrl
            val subUrl = if (questionIdx >= 0) embedUrl.substring(questionIdx + 1) else ""

            // Extract the /e/{id} part
            val eidIdx = srcUrl.indexOf("/e/")
            if (eidIdx < 0) return Result.Error("VidPlay: no /e/ in URL")
            val encodedId = srcUrl.substring(eidIdx + 3).trimEnd('/')

            // Fetch Ciarands keys
            val keysJson = fetch(CIARANDS_KEYS_URL) ?: return Result.Error("VidPlay: keys fetch failed")
            val keysArr = try {
                org.json.JSONArray(keysJson)
            } catch (e: Exception) {
                return Result.Error("VidPlay: keys parse failed")
            }
            val key1 = keysArr.optString(0)
            val key2 = keysArr.optString(1)
            if (key1.isBlank() || key2.isBlank()) return Result.Error("VidPlay: empty keys")

            // RC4 decrypt with key1, then RC4 decrypt with key2
            val decodedId = rc4DecryptBase64Url(encodedId, key1)
            // decodedId is now raw bytes as a string; re-decrypt with key2
            val encodedResult = rc4DecryptRaw(decodedId.toByteArray(Charsets.UTF_8), key2)
            val key = Base64.getEncoder().encodeToString(encodedResult).replace("/", "_")

            // Fetch futoken
            val futokenHtml = fetch(
                "https://vidplay.online/futoken",
                referer = embedUrl
            ) ?: return Result.Error("VidPlay: futoken fetch failed")
            val fuKeyRegex = Regex("""var\s+k\s*=\s*'([^']+)'""")
            val fuKey = fuKeyRegex.find(futokenHtml)?.groupValues?.get(1)
                ?: return Result.Error("VidPlay: no futoken k found")

            // Build data param
            val data = StringBuilder(fuKey).append(",")
            for (i in key.indices) {
                val fuChar = fuKey[i % fuKey.length]
                val keyChar = key[i]
                data.append((fuChar.code + keyChar.code)).append(if (i < key.length - 1) "," else "")
            }

            // Fetch mediainfo
            val mediainfoUrl = "https://vidplay.online/mediainfo/$data" +
                if (subUrl.isNotBlank()) "?$subUrl&autostart=true" else "?autostart=true"
            val mediaJson = fetch(mediainfoUrl, referer = embedUrl)
                ?: return Result.Error("VidPlay: mediainfo fetch failed")
            val mediaResp = try { JSONObject(mediaJson) } catch (e: Exception) {
                return Result.Error("VidPlay: mediainfo parse failed")
            }
            val result = mediaResp.opt("result")
            if (result !is JSONObject) return Result.Error("VidPlay: no result in mediainfo")
            val sourcesArr = result.optJSONArray("sources")
            if (sourcesArr == null || sourcesArr.length() == 0) {
                return Result.Error("VidPlay: no sources in mediainfo")
            }
            val streamUrl = sourcesArr.optJSONObject(0)?.optString("file", "")
            if (streamUrl.isBlank()) return Result.Error("VidPlay: empty stream file")

            Log.d(TAG, "🎬 VidPlay → $streamUrl")
            Result.Stream(streamUrl, vidplayHeaders(embedUrl), "VidSrc.to (VidPlay)")
        } catch (e: Exception) {
            Result.Error("VidPlay: ${e.message}")
        }
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  FileMoon resolver                                                     //
    // ───────────────────────────────────────────────────────────────────────//

    /**
     * Resolve a FileMoon embed URL to a direct `.m3u8` stream.
     *
     * Flow (ported from models/filemoon.py):
     * 1. Fetch the embed page HTML.
     * 2. Find the packed JS `eval(function(p,a,c,k,e,d){...}(args))`.
     * 3. Unpack it (substitute the packed dictionary).
     * 4. Regex out `file:"..."` → the HLS URL.
     */
    private suspend fun resolveFileMoon(embedUrl: String): Result {
        return try {
            val html = fetch(embedUrl, referer = VIDSRC_TO_BASE + "/")
                ?: return Result.Error("FileMoon: page fetch failed")

            // Find packed JS: return p}(...args)
            val packedRegex = Regex(
                """return\s*p\}\((.+)\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val packedMatch = packedRegex.find(html)
                ?: return Result.Error("FileMoon: no packed JS found")
            val unpacked = unpackPacked(packedMatch.groupValues[1])
            if (unpacked.isBlank()) return Result.Error("FileMoon: unpack returned empty")

            // Extract file:"..." (HLS URL)
            val fileRegex = Regex("""file:["']([^"']*)["']""")
            val hlsUrl = fileRegex.find(unpacked)?.groupValues?.get(1)
                ?: return Result.Error("FileMoon: no file URL in unpacked JS")

            Log.d(TAG, "🎬 FileMoon → $hlsUrl")
            Result.Stream(hlsUrl, defaultHeaders(), "VidSrc.to (FileMoon)")
        } catch (e: Exception) {
            Result.Error("FileMoon: ${e.message}")
        }
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  Packed JS unpacker (Dean Edwards' packer)                              //
    // ───────────────────────────────────────────────────────────────────────//

    /**
     * Unpack a Dean Edwards packed JS string.
     *
     * The packed format is: `return p}('packed_string', base, count, 'dict')`
     * where `dict` is a `|`-separated list of tokens. Each token in
     * `packed_string` that matches a base-N representation of its index
     * is replaced with the corresponding dict entry.
     */
    private fun unpackPacked(args: String): String {
        // Parse the args: 'p', a, c, 'k', e, d
        // We only need p (the packed string), a (base), c (count), k (dict)
        val parts = splitPackedArgs(args)
        if (parts.size < 4) return ""
        val p = parts[0]
        val a = parts[1].toIntOrNull() ?: return ""
        val c = parts[2].toIntOrNull() ?: return ""
        val k = parts[3].split("|")

        // For each index from c-1 down to 0, replace base-N representation
        // of i with k[i] in the packed string p.
        var result = p
        for (i in c - 1 downTo 0) {
            if (i < k.size && k[i].isNotBlank()) {
                val token = intToBase(i, a)
                result = result.replace("\\b$token\\b".toRegex(), k[i])
            }
        }
        return result
    }

    /** Split the packed args, handling quoted strings and the pipe-split dict. */
    private fun splitPackedArgs(args: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val n = args.length
        while (i < n && result.size < 4) {
            // Skip whitespace and commas
            while (i < n && (args[i] == ' ' || args[i] == ',')) i++
            if (i >= n) break

            if (args[i] == '\'') {
                // Quoted string
                i++ // skip opening quote
                val start = i
                while (i < n && args[i] != '\'') i++
                val value = args.substring(start, i)
                if (i < n) i++ // skip closing quote
                result.add(value)
            } else {
                // Unquoted (number)
                val start = i
                while (i < n && args[i] != ',' && args[i] != ' ') i++
                result.add(args.substring(start, i))
            }
        }
        // If there are more args after the 4th, grab the rest as the dict
        // (already handled — the 4th arg is the dict, split by |)
        return result
    }

    /** Convert an integer to a base-N string using 0-9a-zA-Z+/ charset. */
    private fun intToBase(x: Int, base: Int): String {
        if (x == 0) return "0"
        val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val sourceBase = charset.substring(0, base)
        var num = x
        val sb = StringBuilder()
        while (num > 0) {
            sb.insert(0, sourceBase[num % base])
            num /= base
        }
        return sb.toString()
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  RC4 decryption                                                        //
    // ───────────────────────────────────────────────────────────────────────//

    /**
     * RC4-decrypt a base64url-encoded string with the given key.
     * (From models/utils.py `decode_url`.)
     */
    private fun rc4DecryptBase64Url(encrypted: String, key: String): String {
        // base64url → base64
        val standardized = encrypted.replace('_', '/').replace('-', '+')
        // Add padding
        val padded = when (standardized.length % 4) {
            2 -> standardized + "=="
            3 -> standardized + "="
            else -> standardized
        }
        val data = Base64.getDecoder().decode(padded)
        val decoded = rc4DecryptRaw(data, key)
        return String(decoded, Charsets.UTF_8)
    }

    /** RC4 (KSA + PRGA) decrypt/encrypt — symmetric, same for both. */
    private fun rc4DecryptRaw(data: ByteArray, key: String): ByteArray {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val s = ByteArray(256) { it.toByte() }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i].toInt() + (keyBytes[i % keyBytes.size].toInt() and 0xff)) and 0xff
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        val decoded = ByteArray(data.size)
        var i = 0
        var k = 0
        for (index in data.indices) {
            i = (i + 1) and 0xff
            k = (k + s[i].toInt()) and 0xff
            val tmp = s[i]; s[i] = s[k]; s[k] = tmp
            val t = (s[i].toInt() + s[k].toInt()) and 0xff
            decoded[index] = (data[index].toInt() xor s[t].toInt()).toByte()
        }
        return decoded
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  HTTP helpers                                                          //
    // ───────────────────────────────────────────────────────────────────────//

    private fun fetch(url: String, referer: String? = null): String? {
        return try {
            val builder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
            if (referer != null) builder.header("Referer", referer)
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "fetch $url → ${resp.code}")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetch $url failed: ${e.message}")
            null
        }
    }

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$VIDSRC_TO_BASE/"
    )

    private fun vidplayHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    // ───────────────────────────────────────────────────────────────────────//
    //  TMDB → IMDb resolution                                               //
    // ───────────────────────────────────────────────────────────────────────//

    private suspend fun resolveImdbId(tmdbId: Int, isTv: Boolean): String? {
        val key = BuildConfig.TMDB_API_KEY
        val resp = if (isTv) {
            ApiClient.tmdbApi.getTvExternalIds(tmdbId, key)
        } else {
            ApiClient.tmdbApi.getMovieExternalIds(tmdbId, key)
        }
        return resp.imdbId
    }
}
