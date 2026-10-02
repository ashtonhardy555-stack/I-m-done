package com.ashtonhardy.piratesfilmcove.data.server

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CinejoyResilientDataSource — makes Cinejoy's CDN usable even when its origin
 * serves **permanently dead HLS segments** (HTTP 502 "origin unavailable").
 *
 * ## The problem this solves
 *
 * Cinejoy's CDN (the backend that serves "Steven Universe Future" and other
 * titles) ships a *partially dead* HLS rendition: a random subset of the video
 * and audio segments 502 from the origin (`cf-cache-status: BYPASS`) while the
 * rest are served from the Cloudflare edge cache (`cf-cache-status: HIT`). The
 * dead set is stable within a session and does NOT recover on retry — so
 * ExoPlayer hits a dead segment, errors out, and the app fails over to the next
 * server. Because every Cinejoy mirror serves the SAME broken rendition, the
 * app ends up cycling through empty 00:00 streams forever.
 *
 * ## The fix (verified against the live CDN)
 *
 * A live probe of Steven Universe Future S1E1 showed:
 *   • 1080p: 12/66 segments dead
 *   • 720p : 17/66 segments dead
 *   • 360p : 12/66 segments dead
 *   • **Every single segment index (0-65) is alive in at least one quality
 *     (66/66 recoverable).**
 *
 * The three video renditions share the SAME segment token and differ ONLY by the
 * `video_1080p_` / `video_720p_` / `video_360p_` label in the filename. So when
 * the requested quality's segment is dead we transparently fetch the SAME
 * segment index from another quality and hand ExoPlayer those bytes. No content
 * is skipped and nothing is lost — the segment is simply sourced from a mirror
 * rendition. This is the "there has to be a way to get all of them" fix.
 *
 * The audio rendition has no alternate quality, so for audio we do the HLS-
 * sanctioned thing instead: any audio segment that is dead is marked with
 * `#EXT-X-GAP` in the rewritten playlist, which tells the player to skip it
 * gracefully (a brief silence) rather than abort the whole playback.
 *
 * Everything is gated on the Cinejoy URL shape (`…/video/{token}/video_{q}_…`
 * for video, an `audio_1_…` media playlist for audio), so every other provider
 * behaves exactly as before.
 */
@UnstableApi
class CinejoyResilientDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val enabled: Boolean = true
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        CinejoyResilientDataSource(upstream.createDataSource(), enabled)
}

@UnstableApi
class CinejoyResilientDataSource(
    private val upstream: DataSource,
    private val enabled: Boolean
) : DataSource {

    /** Buffer used when we serve a (possibly rewritten) playlist from memory. */
    private var playlistBuffer: ByteArray? = null
    private var playlistPos: Int = 0

    private var currentUri: Uri? = null
    private var currentHeaders: Map<String, List<String>> = emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        playlistBuffer = null
        playlistPos = 0

        val original = dataSpec.uri
        val url = original.toString()

        // ── Playlist request? Fetch it whole so we can rewrite it. ──
        if (enabled && isPlaylist(url)) {
            val text = fetchWholePlaylist(dataSpec) ?: return -1L
            val rewritten = if (isAudioMediaPlaylist(text)) {
                insertAudioGaps(text)
            } else {
                text
            }
            val bytes = rewritten.toByteArray(Charsets.UTF_8)
            playlistBuffer = bytes
            playlistPos = dataSpec.position.toInt().coerceIn(0, bytes.size)
            currentUri = original
            return (bytes.size - playlistPos).toLong()
        }

        // ── Segment request (or feature disabled): try original, then mirrors. ──
        val candidates: List<Uri> =
            if (enabled) listOf(original) + alternateQualityUris(original) else listOf(original)

        var lastError: IOException? = null
        for (uri in candidates) {
            try {
                val spec = if (uri == original) dataSpec else dataSpec.withUri(uri)
                val length = upstream.open(spec)
                currentUri = uri
                currentHeaders = upstream.responseHeaders
                if (uri != original) {
                    Log.w(
                        TAG,
                        "cross-quality recovered dead segment: " +
                            "${original.lastPathSegment} -> ${uri.lastPathSegment}"
                    )
                }
                return length
            } catch (e: IOException) {
                lastError = e
                runCatching { upstream.close() }
            }
        }
        throw lastError
            ?: IOException("CinejoyResilientDataSource: all quality candidates failed for $original")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val pb = playlistBuffer
        if (pb != null) {
            if (playlistPos >= pb.size) return -1
            val toCopy = minOf(length, pb.size - playlistPos)
            System.arraycopy(pb, playlistPos, buffer, offset, toCopy)
            playlistPos += toCopy
            return toCopy
        }
        return upstream.read(buffer, offset, length)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = currentHeaders

    override fun close() {
        playlistBuffer = null
        playlistPos = 0
        upstream.close()
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Playlist fetch + audio gap filling                                    //
    // ────────────────────────────────────────────────────────────────────── //

    /** Reads the entire playlist body via [upstream]. Returns null on failure. */
    private fun fetchWholePlaylist(dataSpec: DataSpec): String? {
        return try {
            upstream.open(dataSpec)
            currentUri = dataSpec.uri
            currentHeaders = upstream.responseHeaders
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = upstream.read(buf, 0, buf.size)
                if (n == -1) break
                out.write(buf, 0, n)
            }
            upstream.close()
            out.toString("UTF-8")
        } catch (e: Exception) {
            Log.d(TAG, "playlist fetch failed: ${e.message}")
            runCatching { upstream.close() }
            null
        }
    }

    /**
     * Inserts `#EXT-X-GAP` before every audio segment that is dead on the CDN so
     * the player skips it (brief silence) instead of aborting playback. The
     * segment health is probed in parallel; if the probe fails wholesale we
     * return the playlist untouched (best effort — never worse than before).
     */
    private fun insertAudioGaps(playlist: String): String {
        val lines = playlist.split("\n")
        val segmentUrls = lines
            .map { it.trim() }
            .filter { it.startsWith("http") && it.endsWith(".html") }
        if (segmentUrls.isEmpty()) return playlist

        val dead = findDeadSegments(segmentUrls)
        if (dead.isEmpty()) {
            Log.i(TAG, "audio: all ${segmentUrls.size} segments alive — no gaps needed")
            return playlist
        }
        Log.w(TAG, "audio: marking ${dead.size}/${segmentUrls.size} dead segments as #EXT-X-GAP")

        val sb = StringBuilder(playlist.length + dead.size * 12)
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("http") && t.endsWith(".html") && t in dead) {
                sb.append("#EXT-X-GAP\n")
            }
            sb.append(line).append("\n")
        }
        return sb.toString()
    }

    /**
     * Probes [urls] in parallel and returns the subset that is NOT playable
     * (non-2xx / connection failure). Uses a small thread pool + short timeouts
     * so it never stalls playback for long.
     */
    private fun findDeadSegments(urls: List<String>): Set<String> {
        val pool = Executors.newFixedThreadPool(PROBE_CONCURRENCY)
        return try {
            val tasks = urls.map { url ->
                Callable {
                    val alive = probeAlive(url)
                    if (alive) null else url
                }
            }
            val results = pool.invokeAll(tasks, PROBE_TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val dead = HashSet<String>()
            for ((i, f) in results.withIndex()) {
                try {
                    val v = f.get()
                    if (v != null) dead.add(v)
                } catch (_: Exception) {
                    // Timed out / cancelled — treat as alive to avoid false gaps.
                    Log.d(TAG, "probe inconclusive for ${urls[i]}")
                }
            }
            dead
        } catch (e: Exception) {
            Log.d(TAG, "segment probe failed: ${e.message}")
            emptySet()
        } finally {
            pool.shutdownNow()
        }
    }

    /** True if the segment URL returns a 2xx for a tiny ranged GET. */
    private fun probeAlive(url: String): Boolean {
        val probe = probeSourceFactory.createDataSource()
        return try {
            val spec = DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setPosition(0)
                .setLength(1)
                .build()
            probe.open(spec)
            val buf = ByteArray(1)
            probe.read(buf, 0, 1)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { probe.close() }
        }
    }

    companion object {
        private const val TAG = "CinejoyResilientDS"

        /** Video quality labels, closest-first for dead-segment substitution. */
        private val QUALITIES = listOf("1080p", "720p", "480p", "360p", "240p", "144p")

        private const val PROBE_CONCURRENCY = 16
        private const val PROBE_TOTAL_TIMEOUT_MS = 8_000L

        /**
         * Matches Cinejoy-style video segment / init URLs:
         *   …/video/{token}/video_1080p_008.html
         *   …/video/{token}/video_1080p_init.html
         * Group 1 = everything up to and including "video_", group 2 = quality
         * label, group 3 = the "_008.html" / "_init.html" suffix.
         */
        private val VIDEO_SEG = Regex("""^(.*/video_)(\d{3,4}p)(_(?:init|\d+)\.html)$""")

        /** A playlist (master or media) — we fetch these whole to rewrite them. */
        private fun isPlaylist(url: String): Boolean =
            url.contains(".m3u8") || url.contains("playlist") || url.contains("/master")

        /** An AUDIO media playlist (the one that needs #EXT-X-GAP handling). */
        private fun isAudioMediaPlaylist(text: String): Boolean =
            text.contains("#EXTINF") && text.contains("audio_1_")

        /**
         * Alternate-quality URIs for a Cinejoy video segment: same token, same
         * segment index, different quality label. Empty for anything else.
         */
        fun alternateQualityUris(uri: Uri): List<Uri> {
            val s = uri.toString()
            val m = VIDEO_SEG.find(s) ?: return emptyList()
            val prefix = m.groupValues[1]
            val current = m.groupValues[2]
            val suffix = m.groupValues[3]
            return QUALITIES.filter { it != current }
                .map { Uri.parse("$prefix$it$suffix") }
        }

        /**
         * A dedicated DataSource.Factory used for the tiny segment-health probe.
         * Kept separate so probe traffic never shares state with playback.
         */
        internal var probeSourceFactory: DataSource.Factory = DataSource.Factory {
            throw IllegalStateException("CinejoyResilientDataSource.probeSourceFactory not set")
        }
    }
}
