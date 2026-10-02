package com.ashtonhardy.piratesfilmcove.data.log

import android.os.Build
import android.util.Log
import com.ashtonhardy.piratesfilmcove.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Sends playback debug logs to GitHub so the developer can see, for every
 * title, whether it **PLAYED**, **FAILED** to play, or was **SKIPPED**, and on
 * which **server** it happened.
 *
 * ## Where the logs go
 * Each event is appended as a comment on a dedicated issue in the repo
 * (`BuildConfig.GH_LOG_REPO` / `GH_LOG_ISSUE`). Comments are append-only, so
 * there is no read-modify-write race between devices.
 *
 * ## Token
 * The GitHub token is injected at BUILD time from the `GH_LOG_TOKEN` CI secret
 * into `BuildConfig.GH_LOG_TOKEN`, so it is never hard-coded in source. It only
 * needs "Issues: read and write" on this one repository.
 *
 * If the token is blank (e.g. a local build, or the secret is not set) the
 * logger SILENTLY degrades to Logcat-only — it never throws and never blocks
 * playback. All network work happens off the main thread.
 */
object PlaybackLogger {

    private const val TAG = "PlaybackLogger"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Formatting helpers ─────────────────────────────────────────────── //

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun deviceTag(): String {
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return "$model · Android ${Build.VERSION.RELEASE}"
    }

    private fun appTag(): String =
        "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    private fun seconds(ms: Long): String =
        String.format(Locale.US, "%.1fs", ms / 1000.0)

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun titleTag(
        title: String,
        contentType: String,
        season: Int,
        episode: Int,
        tmdbId: Int
    ): String {
        val t = title.ifBlank { "(unknown title)" }
        val ep = if (contentType.equals("tv", ignoreCase = true)) " S${season}E${episode}" else ""
        return "**$t**$ep (tmdb $tmdbId)"
    }

    private fun serverTag(server: String): String = "server `${server.ifBlank { "?" }}`"

    // ── Public API ─────────────────────────────────────────────────────── //

    /** A stream actually started playing. */
    fun logPlayed(
        title: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int,
        server: String,
        url: String,
        positionMs: Long
    ) {
        emit(
            "PLAYED",
            listOf(
                titleTag(title, contentType, season, episode, tmdbId),
                serverTag(server),
                "host ${hostOf(url)}",
                "pos ${seconds(positionMs)}"
            )
        )
    }

    /** A stream could not be played at all (fatal error). */
    fun logFailed(
        title: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int,
        server: String,
        error: String,
        positionMs: Long
    ) {
        emit(
            "FAILED",
            listOf(
                titleTag(title, contentType, season, episode, tmdbId),
                serverTag(server),
                "error `${error.take(300)}`",
                "pos ${seconds(positionMs)}"
            )
        )
    }

    /** A source was skipped in favour of another (or a dead segment was jumped). */
    fun logSkipped(
        title: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int,
        server: String,
        nextServer: String,
        reason: String,
        positionMs: Long
    ) {
        val move = if (nextServer.isNotBlank() && nextServer != server) {
            "server `${server.ifBlank { "?" }}` → `${nextServer}`"
        } else {
            serverTag(server)
        }
        emit(
            "SKIPPED",
            listOf(
                titleTag(title, contentType, season, episode, tmdbId),
                move,
                "reason `$reason`",
                "pos ${seconds(positionMs)}"
            )
        )
    }

    // ── Internals ──────────────────────────────────────────────────────── //

    private fun emit(event: String, parts: List<String>) {
        val line = "**[${event}]** `${timestamp()}` — " +
            parts.joinToString(" · ") +
            " · app ${appTag()} · device ${deviceTag()}"

        // Always write to Logcat so `adb logcat -s PlaybackLogger` works even
        // when the GitHub token is not configured.
        Log.i(TAG, line)

        val token = BuildConfig.GH_LOG_TOKEN
        if (token.isBlank()) return
        scope.launch { postComment(line, token) }
    }

    private fun postComment(line: String, token: String) {
        val url = "https://api.github.com/repos/${BuildConfig.GH_LOG_REPO}" +
            "/issues/${BuildConfig.GH_LOG_ISSUE}/comments"
        val body = JSONObject().put("body", line).toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "log POST HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "log POST failed: ${e.message}")
        }
    }
}
