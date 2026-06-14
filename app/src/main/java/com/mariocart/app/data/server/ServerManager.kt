package com.mariocart.app.data.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.repository.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ServerManager {

    private const val TAG = "ServerManager"
    private const val HEALTH_TIMEOUT_SECONDS = 6L
    private const val PREFS_NAME = "server_prefs"
    private const val KEY_DISCOVERED = "discovered_servers"
    private const val KEY_DEAD = "dead_servers"
    private const val KEY_LAST_CHECK = "last_check"

    // Known search URLs for discovering embed servers
    private val discoverySearchUrls = listOf(
        "https://www.google.com/search?q=free+movie+embed+api+vidsrc+site",
        "https://www.google.com/search?q=tmdb+embed+streaming+server+2024+2025",
        "https://www.google.com/search?q=vidsrc+alternative+embed+movie+api"
    )

    // Known embed URL patterns to detect servers from web scraping
    private val embedPatterns = listOf(
        Regex("""https?://[a-zA-Z0-9.-]+\.[a-z]{2,}/embed""", RegexOption.IGNORE_CASE),
        Regex("""https?://[a-zA-Z0-9.-]+\.[a-z]{2,}/e/""", RegexOption.IGNORE_CASE),
        Regex("""https?://[a-zA-Z0-9.-]+\.[a-z]{2,}/v/""", RegexOption.IGNORE_CASE)
    )

    // Well-known aggregator pages that list embed servers
    private val aggregatorUrls = listOf(
        "https://raw.githubusercontent.com/ashtonhardy555-stack/I-m-done/main/servers.json",
        "https://raw.githubusercontent.com/movie-web/providers/main/README.md"
    )

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _liveServers = MutableStateFlow<List<StreamingServer>>(emptyList())
    val liveServers: StateFlow<List<StreamingServer>> = _liveServers

    private val _deadServers = MutableStateFlow<Set<String>>(emptySet())
    val deadServers: StateFlow<Set<String>> = _deadServers

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    private val mutex = Mutex()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun initialize(context: Context) {
        init(context)
        mutex.withLock {
            val lastCheck = prefs?.getLong(KEY_LAST_CHECK, 0L) ?: 0L
            // Only re-check every 10 minutes
            if (_liveServers.value.isNotEmpty() &&
                System.currentTimeMillis() - lastCheck < 600_000
            ) return
        }

        _isChecking.value = true
        _statusMessage.value = "Checking servers..."

        try {
            // 1. Start with builtin servers
            val allServers = mutableListOf<StreamingServer>()
            allServers.addAll(ContentRepository().streamingServers)

            // 2. Load previously discovered servers from cache
            loadCachedServers()?.let { cached ->
                val existingUrls = allServers.map { it.baseUrl }.toSet()
                cached.filter { it.baseUrl !in existingUrls }.let { allServers.addAll(it) }
            }

            // 3. Try to discover new servers from the web
            _statusMessage.value = "Searching for new servers..."
            val discovered = discoverServersFromWeb()
            val existingUrls = allServers.map { it.baseUrl }.toSet()
            val newServers = discovered.filter { it.baseUrl !in existingUrls }
            if (newServers.isNotEmpty()) {
                Log.d(TAG, "Discovered ${newServers.size} new servers from web")
                allServers.addAll(newServers)
            }

            // 4. Health check all servers
            _statusMessage.value = "Testing ${allServers.size} servers..."
            val results = healthCheckAll(allServers)

            val live = mutableListOf<StreamingServer>()
            val dead = mutableSetOf<String>()

            results.forEach { (server, isAlive) ->
                if (isAlive) live.add(server)
                else dead.add(server.name)
            }

            _liveServers.value = live
            _deadServers.value = dead
            _statusMessage.value = "${live.size} servers online"

            // 5. Cache the results
            saveCachedServers(live)
            saveDeadServers(dead)
            prefs?.edit()?.putLong(KEY_LAST_CHECK, System.currentTimeMillis())?.apply()

            Log.d(TAG, "Health check complete: ${live.size} live, ${dead.size} dead")
        } catch (e: Exception) {
            Log.e(TAG, "Init error, using builtins", e)
            if (_liveServers.value.isEmpty()) {
                _liveServers.value = ContentRepository().streamingServers
            }
            _statusMessage.value = "Using default servers"
        } finally {
            _isChecking.value = false
        }
    }

    private suspend fun discoverServersFromWeb(): List<StreamingServer> =
        withContext(Dispatchers.IO) {
            val found = mutableMapOf<String, String>() // baseUrl -> name

            // Try each aggregator/discovery source
            for (url in aggregatorUrls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
                        )
                        .build()
                    val response = healthClient.newCall(request).execute()
                    val body = response.body?.string() ?: continue
                    response.close()

                    // Try parsing as JSON array first
                    if (body.trimStart().startsWith("[")) {
                        try {
                            val arr = org.json.JSONArray(body)
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val name = obj.optString("name", "")
                                val baseUrl = obj.optString("baseUrl", "")
                                if (name.isNotEmpty() && baseUrl.isNotEmpty()) {
                                    found[baseUrl] = name
                                }
                            }
                        } catch (_: Exception) { }
                    }

                    // Also scan for embed URLs in the text
                    extractEmbedUrls(body, found)
                } catch (e: Exception) {
                    Log.d(TAG, "Discovery source failed: $url - ${e.message}")
                }
            }

            // Search the web for more embed servers
            for (searchUrl in discoverySearchUrls) {
                try {
                    val request = Request.Builder()
                        .url(searchUrl)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                        .build()
                    val response = healthClient.newCall(request).execute()
                    val body = response.body?.string() ?: continue
                    response.close()
                    extractEmbedUrls(body, found)
                } catch (e: Exception) {
                    Log.d(TAG, "Web search failed: ${e.message}")
                }
            }

            found.map { (baseUrl, name) -> StreamingServer(name, baseUrl) }
        }

    private fun extractEmbedUrls(html: String, into: MutableMap<String, String>) {
        for (pattern in embedPatterns) {
            pattern.findAll(html).forEach { match ->
                val url = match.value.trimEnd('/')
                // Filter out known non-streaming domains
                val dominated = listOf(
                    "google", "youtube", "facebook", "twitter",
                    "instagram", "tiktok", "reddit", "wikipedia",
                    "github.com", "stackoverflow", "amazon"
                )
                val dominated2 = dominated.any { url.contains(it) }
                if (!dominated2 && url !in into) {
                    val host = try {
                        android.net.Uri.parse(url).host?.replace("www.", "") ?: url
                    } catch (_: Exception) { url }
                    val name = host.split(".").first()
                        .replaceFirstChar { it.uppercase() }
                    into[url] = name
                }
            }
        }
    }

    private suspend fun healthCheckAll(
        servers: List<StreamingServer>
    ): List<Pair<StreamingServer, Boolean>> = coroutineScope {
        servers.map { server ->
            async(Dispatchers.IO) {
                server to checkServer(server)
            }
        }.awaitAll()
    }

    private fun checkServer(server: StreamingServer): Boolean {
        return try {
            val testUrl = server.movieUrl(550) // Fight Club as test
            val request = Request.Builder()
                .url(testUrl)
                .head()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
                )
                .build()
            val response = healthClient.newCall(request).execute()
            val code = response.code
            response.close()
            code in 200..499
        } catch (_: Exception) {
            false
        }
    }

    fun markServerDead(serverName: String) {
        _deadServers.value = _deadServers.value + serverName
        _liveServers.value = _liveServers.value.filter { it.name != serverName }
        val dead = prefs?.getStringSet(KEY_DEAD, emptySet())?.toMutableSet() ?: mutableSetOf()
        dead.add(serverName)
        prefs?.edit()?.putStringSet(KEY_DEAD, dead)?.apply()
    }

    fun getOrderedServers(): List<StreamingServer> {
        val live = _liveServers.value
        return if (live.isNotEmpty()) live else ContentRepository().streamingServers
    }

    private fun saveCachedServers(servers: List<StreamingServer>) {
        val json = org.json.JSONArray()
        servers.forEach { s ->
            val obj = org.json.JSONObject()
            obj.put("name", s.name)
            obj.put("baseUrl", s.baseUrl)
            json.put(obj)
        }
        prefs?.edit()?.putString(KEY_DISCOVERED, json.toString())?.apply()
    }

    private fun loadCachedServers(): List<StreamingServer>? {
        val raw = prefs?.getString(KEY_DISCOVERED, null) ?: return null
        return try {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<StreamingServer>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    StreamingServer(
                        name = obj.getString("name"),
                        baseUrl = obj.getString("baseUrl")
                    )
                )
            }
            list
        } catch (_: Exception) { null }
    }

    private fun saveDeadServers(dead: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_DEAD, dead)?.apply()
    }
}
