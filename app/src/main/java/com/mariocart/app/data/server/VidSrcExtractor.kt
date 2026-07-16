package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * VidSrcExtractor — resolves a **direct playable** HLS stream URL from the
 * VidSrc embed pipeline (`vidsrc.me` → `cloudorchestranova.com` RCP/PRORCP),
 * the same backend that powers dozens of "hidden server" streaming sites.
 *
 * ## Why this extractor exists
 *
 * The VidStorm API (the primary stage) is broken for a large set of popular
 * titles: for movies like *Interstellar* (TMDB 157336) and *The Green Mile*
 * (TMDB 497) it returns only a single, dead "Boron" source whose Cloudflare
 * Worker URL responds `HTTP 404` with a body of `.`. Every other named source
 * (Lithium, Hydrogen, Helium, …) comes back with `url: null`. The user ends
 * up waiting ~18 s for the embed WebView fallback, which then also fails and
 * leaves them on a black screen.
 *
 * The VidSrc pipeline, by contrast, consistently resolves these same titles
 * to **two** live `#EXTM3U` manifests at 360p/720p/1080p — verified live
 * against Interstellar, The Green Mile, Breaking Bad S1E1 and Revolution
 * S1E1 during development. It runs entirely over plain OkHttp (no WebView,
 * no JS challenge solving), so it is both fast (~3 round-trips) and reliable.
 *
 * ## How it works (reverse-engineered from the live `cloudorchestranova.com` HTML)
 *
 *  1. `GET https://vidsrc.me/embed/{movie|tv}?tmdb={id}[&season={s}&episode={e}]`
 *     → HTML page containing an `<iframe src="https://cloudorchestranova.com/rcp/{hash}">`
 *     and a `.serversList .server[data-hash="…"]` list. We capture the
 *     iframe origin as `BASEDOM` and every server hash.
 *
 *  2. For each server hash: `GET {BASEDOM}/rcp/{hash}` → a small JS blob
 *     containing `src: '/prorcp/{hash2}'`.
 *
 *  3. `GET {BASEDOM}/prorcp/{hash2}` → the player HTML. Its inline `<script>`
 *     contains `var master_urls = "URL1 or URL2 or URL3"` — the direct
 *     master playlist URLs, each suffixed with `?token=__TOKEN__` (movies)
 *     or a mix of `?token=__TOKEN__` / `?token=__TOKENPG__` (TV).
 *
 *  4. The token is fetched from a `generate.php` endpoint. The inline script
 *     calls `$.get("https://{host}/generate.php", token => …)` and does
 *     `master_urls.replaceAll("__TOKEN__", token)`. We mirror that: find the
 *     `generate.php` hosts in the inline script, fetch each token, and
 *     substitute every `__TOKEN*__` placeholder.
 *
 *  5. The remaining URLs (split on ` or `) are individually verified for a
 *     `#EXTM3U` body and the first good one is returned. ExoPlayer plays it
 *     directly with a `Referer` of the BASEDOM origin (the CDN requires it).
 *
 * This is the "hidden servers in the exoplayer" the user asked about: the
 * VidSrc/FilmCave-family aggregator sites keep a second, richer set of
 * direct CDN streams behind this RCP indirection that the VidStorm API never
 * exposes.
 */
object VidSrcExtractor {

    private const val TAG = "VidSrc"

    private const val EMBED_BASE = "https://vidsrc.me/embed"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            // 8s/10s — slightly tighter than the original 10s/15s so a
            // single slow hop in the 5-round-trip pipeline (embed→RCP→
            // PRORCP→generate.php→verify) doesn't balloon the total.
            // On a fast connection the whole chain is ~1.8s; on 3G it's
            // ~6-10s. These timeouts ensure we fail fast on a truly dead
            // host rather than waiting 15s per hop.
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** A shorter-timeout client for the lightweight URL verification. */
    private val verifier by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            // 4s/6s — tight connect timeout so a dead CDN host fails fast;
            // slightly longer read timeout because a valid HLS master
            // playlist can be a few KB and on a slow mobile connection
            // transferring it may take a moment.
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Result type                                                          //
    // ─────────────────────────────────────────────────────────────────────── //

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidSrc"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Public API                                                           //
    // ─────────────────────────────────────────────────────────────────────── //

    /**
     * Resolve a direct playable stream for the given TMDB content.
     *
     * @param tmdbId       TMDB id of the movie or TV show.
     * @param contentType  "movie" or "tv".
     * @param season       season number (tv only).
     * @param episode      episode number (tv only).
     */
    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): Result = withContext(Dispatchers.IO) {
        val type = if (contentType == "tv") "tv" else "movie"
        val embedUrl = if (type == "tv") {
            "$EMBED_BASE/tv?tmdb=$tmdbId&season=$season&episode=$episode"
        } else {
            "$EMBED_BASE/movie?tmdb=$tmdbId"
        }
        Log.d(TAG, "🔍 VidSrc embed: $embedUrl")

        // Step 1: fetch the embed page.
        val embedHtml = try {
            fetch(embedUrl, referer = "https://vidsrc.me/")
        } catch (e: Exception) {
            Log.w(TAG, "VidSrc embed fetch failed: ${e.message}")
            return@withContext Result.Error("VidSrc: embed unreachable")
        }
        if (embedHtml.length < 500) {
            return@withContext Result.Error("VidSrc: empty embed page")
        }

        // Step 2: find BASEDOM from the iframe src.
        val baseDom = findIframeOrigin(embedHtml)
            ?: return@withContext Result.Error("VidSrc: no iframe in embed")
        Log.d(TAG, "VidSrc BASEDOM: $baseDom")

        // Step 3: collect server data-hash values.
        val serverHashes = findServerHashes(embedHtml)
        if (serverHashes.isEmpty()) {
            return@withContext Result.Error("VidSrc: no servers in embed")
        }
        Log.d(TAG, "VidSrc found ${serverHashes.size} server(s)")

        // Walk through servers; return the first verified stream.
        for ((idx, serverHash) in serverHashes.withIndex()) {
            val stream = tryServer(baseDom, serverHash, idx, serverHashes.size)
            if (stream != null) {
                Log.i(TAG, "✅ VidSrc resolved: ${stream.url}")
                return@withContext stream
            }
        }

        Result.Error("VidSrc: no playable stream found")
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Per-server pipeline                                                  //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun tryServer(
        baseDom: String,
        serverHash: String,
        idx: Int,
        total: Int
    ): Result.Stream? {
        // Step 4: RCP
        val rcpUrl = "$baseDom/rcp/$serverHash"
        val rcpHtml = try {
            fetch(rcpUrl, referer = "https://vidsrcme.ru/")
        } catch (e: Exception) {
            Log.w(TAG, "VidSrc RCP fetch failed (server ${idx + 1}/$total): ${e.message}")
            return null
        }

        // Step 5: find src: '/prorcp/{hash}'
        val prorcpHash = findProrcpHash(rcpHtml)
        if (prorcpHash == null) {
            Log.d(TAG, "VidSrc server ${idx + 1}: no prorcp src")
            return null
        }

        // Step 6: PRORCP
        val prorcpUrl = "$baseDom/prorcp/$prorcpHash"
        val prorcpHtml = try {
            fetch(prorcpUrl, referer = "$baseDom/")
        } catch (e: Exception) {
            Log.w(TAG, "VidSrc PRORCP fetch failed: ${e.message}")
            return null
        }

        // Step 7: extract master_urls var
        val masterStr = findMasterUrls(prorcpHtml)
        if (masterStr == null) {
            Log.d(TAG, "VidSrc server ${idx + 1}: no master_urls in PRORCP")
            return null
        }
        Log.d(TAG, "VidSrc master_urls: ${masterStr.length} chars")

        // Step 8: resolve __TOKEN*__ placeholders via generate.php endpoints.
        val resolved = resolveTokens(masterStr, prorcpHtml, baseDom)

        // Step 9: split, verify each candidate, return first good one.
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to baseDom,
            "Origin" to baseDom
        )
        val urls = resolved.split(" or ").map { it.trim() }.filter { it.startsWith("http") }
        Log.d(TAG, "VidSrc ${urls.size} candidate URL(s) after token resolve")

        // Track the best "looks valid but unverified" URL as a last-resort
        // fallback. If every verifyHls() fails (e.g. a transient SSL/timeout
        // hiccup on a mobile network, or a CDN that 200s a non-EXTM3U body on
        // a ranged GET), we still prefer a well-formed VidSrc URL over
        // falling through to the embed stage — the user confirmed embed
        // servers "usually never work".
        var bestUnverified: String? = null

        for ((ui, url) in urls.withIndex()) {
            if (url.contains("__")) {
                Log.d(TAG, "VidSrc URL ${ui + 1} still has placeholder, skipping")
                continue
            }
            if (looksLikeValidHls(url) && bestUnverified == null) {
                bestUnverified = url
            }
            if (verifyHls(url, headers)) {
                return Result.Stream(
                    url = url,
                    headers = headers,
                    providerName = "VidSrc·${idx + 1}"
                )
            }
            Log.d(TAG, "VidSrc URL ${ui + 1} failed HLS verification")
        }
        // Last resort: return the best-looking unverified URL. ExoPlayer will
        // attempt to play it; if the CDN is truly down it will error out, but
        // that is still better than skipping to the embed stage which the user
        // says almost never works.
        if (bestUnverified != null) {
            Log.w(TAG, "VidSrc: no URL passed HLS verify; returning best unverified: ${bestUnverified!!.take(80)}")
            return Result.Stream(
                url = bestUnverified!!,
                headers = headers,
                providerName = "VidSrc·${idx + 1}·unverified"
            )
        }
        return null
    }

    /**
     * Heuristic: does this resolved URL look like a real HLS master playlist?
     * Used to pick a last-resort URL when live verification fails. A valid
     * VidSrc stream URL ends in `.m3u8` (possibly with a `?token=...` query)
     * and has no remaining `__PLACEHOLDER__` tokens.
     */
    private fun looksLikeValidHls(url: String): Boolean {
        if (url.contains("__")) return false
        val noQuery = url.substringBefore('?')
        return noQuery.endsWith(".m3u8", ignoreCase = true)
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Token resolution                                                     //
    // ─────────────────────────────────────────────────────────────────────── //

    /**
     * Replaces every `__TOKEN*__` placeholder in [masterStr] with a real token
     * fetched from the `generate.php` endpoints referenced in [prorcpHtml].
     *
     * The PRORCP inline script looks like:
     *   $.get("https://HOST/generate.php", function(token){
     *       master_urls = master_urls.replaceAll("__TOKEN__",token);
     *   });
     *   $.get("https://OTHER_HOST/generate.php", function(token){
     *       master_urls = master_urls.replaceAll("__TOKENPG__",token);
     *   });
     *
     * We collect every `generate.php` host and every `replaceAll("___…___",`
     * placeholder in order, and pair them up. As a fallback we also try the
     * host of each individual stream URL (the CDN that serves the m3u8 also
     * serves generate.php).
     */
    private fun resolveTokens(
        masterStr: String,
        prorcpHtml: String,
        baseDom: String
    ): String {
        if (!masterStr.contains("__")) return masterStr

        val genHosts = GEN_HOST_PATTERN.matcher(prorcpHtml).let { m ->
            val list = mutableListOf<String>()
            while (m.find()) {
                val host = m.group(1)!!
                // Filter out JS-concatenation artifacts like `"+host+"` that
                // slip through when the inline script builds the URL with a
                // variable (e.g. `$.get("https://"+host+"/generate.php")`).
                if ('+' !in host && '"' !in host && host.contains('.')) {
                    list += host
                }
            }
            list
        }
        val placeholders = REPLACEALL_PATTERN.matcher(prorcpHtml).let { m ->
            val list = mutableListOf<String>()
            while (m.find()) list += m.group(1)!!
            list
        }
        // Distinct placeholders actually present in the URLs (e.g. TOKEN, TOKENPG).
        val urlPlaceholders = PLACEHOLDER_PATTERN.matcher(masterStr).let { m ->
            val set = linkedSetOf<String>()
            while (m.find()) set += m.group(1)!!
            set
        }

        Log.d(TAG, "VidSrc gen.php hosts=$genHosts placeholders=$placeholders urlPHs=$urlPlaceholders")

        var result = masterStr
        val usedHosts = mutableSetOf<String>()

        // ── PRIMARY: per-URL host resolution ──
        //
        // The MOST reliable way to resolve a token is to ask the SAME host
        // that serves the m3u8 for its generate.php token. The CDN that hosts
        // `https://noosphere-nectar.site/pl/.../master.m3u8?token=__TOKEN__`
        // always also serves `https://noosphere-nectar.site/generate.php`,
        // and the token it returns is valid for its own URLs. This mirrors the
        // JS's third `$.get("https://"+host+"/generate.php", …)` call which
        // runs AFTER the explicit `$.get("https://HOST/generate.php", …)`
        // calls — but for our purposes doing it FIRST is more reliable because
        // it eliminates the chance of the heuristic mis-pairing TOKEN with a
        // putgate token (which 403s on non-putgate CDN URLs) or vice-versa.
        //
        // We walk each URL that still has a placeholder, fetch the token from
        // that URL's own host, and substitute. Only if this leaves unresolved
        // placeholders do we fall back to the heuristic below.
        for (raw in result.split(" or ")) {
            val url = raw.trim()
            if (!url.startsWith("http") || !url.contains("__")) continue
            val host = extractHost(url) ?: continue
            if (host in usedHosts) continue
            val token = try {
                fetch("https://$host/generate.php", referer = "$baseDom/").trim()
            } catch (e: Exception) { "" }
            if (token.isNotBlank()) {
                Log.d(TAG, "VidSrc per-URL token from $host: ${token.take(40)}…")
                // Replace every remaining placeholder in this URL with this
                // host's token (a given CDN host only ever uses one token type
                // for its own URLs — e.g. noosphere-nectar.site uses __TOKEN__,
                // app2.putgate.com uses __TOKENPG__).
                val phsInUrl = PLACEHOLDER_PATTERN.matcher(url).let { m ->
                    val set = linkedSetOf<String>()
                    while (m.find()) set += m.group(1)!!
                    set
                }
                for (ph in phsInUrl) {
                    result = result.replace("__${ph}__", token)
                }
                usedHosts += host
                if (!result.contains("__")) break
            }
        }

        // ── FALLBACK: heuristic host selection ──
        //
        // If per-URL resolution didn't clear all placeholders (e.g. a URL's
        // own generate.php was down), use the heuristic: pair __TOKENPG__ →
        // putgate host, __TOKEN__ → first non-putgate host. This is less
        // reliable than per-URL (it can mis-pair) but covers edge cases where
        // the CDN's generate.php is temporarily unreachable.
        if (result.contains("__")) {
            for (ph in urlPlaceholders) {
                val fullPlaceholder = "__${ph}__"
                if (!result.contains(fullPlaceholder)) continue
                val host = pickHostForPlaceholder(ph, genHosts, placeholders, result, usedHosts)
                    ?: continue
                val tokenUrl = "https://$host/generate.php"
                val token = try {
                    fetch(tokenUrl, referer = "$baseDom/").trim()
                } catch (e: Exception) {
                    Log.w(TAG, "VidSrc fallback token fetch $host failed: ${e.message}")
                    ""
                }
                if (token.isNotBlank()) {
                    Log.d(TAG, "VidSrc fallback token for $fullPlaceholder from $host: ${token.take(40)}…")
                    result = result.replace(fullPlaceholder, token)
                    usedHosts += host
                }
            }
        }
        return result
    }

    /**
     * Heuristic host selection for a placeholder name.
     *  - `__TOKENPG__` → the putgate.com host (PG suffix convention).
     *  - otherwise → the first generate.php host not yet used (or the last
     *    one if there's only one).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun pickHostForPlaceholder(
        ph: String,
        genHosts: List<String>,
        placeholders: List<String>,
        masterStr: String,
        usedHosts: Set<String>
    ): String? {
        if (ph == "TOKENPG") {
            return genHosts.firstOrNull { it.contains("putgate") }
                ?: genHosts.firstOrNull { it !in usedHosts }
                ?: genHosts.firstOrNull()
        }
        // For the main __TOKEN__, prefer a non-putgate generate.php host
        // (the putgate token is PG-specific and tends to 403 on the CDN URLs).
        val candidates = genHosts.filter { !it.contains("putgate") && it !in usedHosts }
        if (candidates.isNotEmpty()) return candidates.first()
        val remaining = genHosts.filter { it !in usedHosts }
        if (remaining.isNotEmpty()) return remaining.first()
        return genHosts.lastOrNull()
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  HTML parsing helpers                                                 //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun findIframeOrigin(html: String): String? {
        val m = IFRAME_SRC_PATTERN.matcher(html)
        if (m.find()) {
            var src = m.group(1)!!
            if (src.startsWith("//")) src = "https:$src"
            val schemeEnd = src.indexOf("://")
            if (schemeEnd < 0) return null
            val hostStart = schemeEnd + 3
            val pathStart = src.indexOf('/', hostStart)
            val origin = if (pathStart > 0) src.substring(0, pathStart) else src
            return origin
        }
        // Keyword fallback: the iframe src is sometimes protocol-relative
        // ("//cloudorchestranova.com/...") or embedded in a JS string in a way
        // the iframe regex misses. The VidSrc family always uses a
        // cloudorchestranova.com (or similar) RCP host, so if we can see the
        // keyword in the HTML, use it directly.
        val keywordHosts = listOf(
            "cloudorchestranova.com",
            "cloudfoxreborn.com",
            "cloud9sparks.com"
        )
        for (host in keywordHosts) {
            if (html.contains(host, ignoreCase = true)) {
                Log.d(TAG, "VidSrc findIframeOrigin: iframe regex missed, using keyword host https://$host")
                return "https://$host"
            }
        }
        return null
    }

    private fun findServerHashes(html: String): List<String> {
        val m = DATA_HASH_PATTERN.matcher(html)
        val list = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        while (m.find()) {
            val h = m.group(1)!!
            if (seen.add(h)) list += h
        }
        return list
    }

    private fun findProrcpHash(rcpHtml: String): String? {
        SRC_COLON_PATTERN.matcher(rcpHtml).let { m ->
            if (m.find()) {
                val s = m.group(1)!!
                if (s.startsWith("/prorcp/")) return s.removePrefix("/prorcp/")
            }
        }
        SRC_QUOTE_PATTERN.matcher(rcpHtml).let { m ->
            if (m.find()) {
                val s = m.group(1)!!
                if (s.startsWith("/prorcp/")) return s.removePrefix("/prorcp/")
            }
        }
        return null
    }

    private fun findMasterUrls(prorcpHtml: String): String? {
        MASTER_URLS_PATTERN.matcher(prorcpHtml).let { m ->
            if (m.find()) return m.group(1)
        }
        // Fallback: any quoted string containing ".m3u8"
        M3U8_QUOTED_PATTERN.matcher(prorcpHtml).let { m ->
            if (m.find()) return m.group(1)
        }
        return null
    }

    private fun extractHost(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val hostStart = schemeEnd + 3
        val pathStart = url.indexOf('/', hostStart)
        return if (pathStart > 0) url.substring(hostStart, pathStart)
        else url.substring(hostStart)
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Verification                                                         //
    // ─────────────────────────────────────────────────────────────────────── //

    /**
     * Lightweight HLS verification: a small full GET that must return 2xx
     * and contain `#EXTM3U` in the body. We deliberately do NOT send a Range
     * header (ranged GETs return 206 for the dead `.` bodies we want to
     * reject — the same Interstellar bug VidStorm had).
     */
    private fun verifyHls(url: String, headers: Map<String, String>): Boolean {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.get()
            verifier.newCall(builder.build()).execute().use { resp ->
                if (resp.code !in 200..299) {
                    Log.d(TAG, "VidSrc verify: HTTP ${resp.code}")
                    return false
                }
                val body = resp.body?.string().orEmpty()
                val ok = body.contains("#EXTM3U")
                Log.d(TAG, "VidSrc verify: ${body.length} chars, EXTM3U=$ok")
                ok
            }
        } catch (e: Exception) {
            Log.d(TAG, "VidSrc verify: connection failed (${e.message})")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  HTTP helper                                                          //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun fetch(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", referer)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Compiled regex patterns                                              //
    // ─────────────────────────────────────────────────────────────────────── //

    private val IFRAME_SRC_PATTERN =
        Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)

    private val DATA_HASH_PATTERN =
        Pattern.compile("data-hash=\"([^\"]+)\"")

    private val SRC_COLON_PATTERN =
        Pattern.compile("src:\\s*'([^']*)'")

    private val SRC_QUOTE_PATTERN =
        Pattern.compile("src[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']")

    // master_urls = "… or … or …"  (greedy on quotes, DOTALL for newlines)
    private val MASTER_URLS_PATTERN =
        Pattern.compile("master_urls\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL)

    private val M3U8_QUOTED_PATTERN =
        Pattern.compile("\"(https?://[^\"]+\\.m3u8[^\"]*)\"")

    // Matches both $.get("https://HOST/generate.php") and $.ajax("...").
    // Some VidSrc-family pages use $.ajax instead of $.get for the token
    // endpoint, so we accept either to avoid missing a generate.php host.
    private val GEN_HOST_PATTERN =
        Pattern.compile("\\$\\.(?:get|ajax)\\(\"https?://([^/]+)/generate\\.php\"")

    private val REPLACEALL_PATTERN =
        Pattern.compile("replaceAll\\(\"(__\\w+__)\"")

    private val PLACEHOLDER_PATTERN =
        Pattern.compile("__(\\w+)__")
}
