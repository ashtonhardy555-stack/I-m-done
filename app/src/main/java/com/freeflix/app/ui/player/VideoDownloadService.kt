package com.freeflix.app.ui.player

import android.content.Context
import android.util.Log
import com.freeflix.app.data.cache.VideoCacheManager
import com.freeflix.app.data.model.StreamingServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service to download videos from streaming servers/APIs directly
 * Integrates with VideoCacheManager for automatic caching and cleanup
 */
class VideoDownloadService(private val context: Context) {
    private val cacheManager = VideoCacheManager.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val TAG = "VideoDownloadService"

        private var instance: VideoDownloadService? = null

        fun getInstance(context: Context): VideoDownloadService {
            return instance ?: synchronized(this) {
                VideoDownloadService(context).also { instance = it }
            }
        }
    }

    /**
     * Fetch video URL from streaming server API
     */
    suspend fun fetchVideoUrlFromServer(
        server: StreamingServer,
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val videoUrl = when (contentType.lowercase()) {
                "tv" -> server.tvUrl(tmdbId, season, episode)
                else -> server.movieUrl(tmdbId)
            }

            Log.d(TAG, "Fetching from: $videoUrl")

            val request = Request.Builder()
                .url(videoUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                response.close()

                // Try to extract video URL from response
                val extractedUrl = extractVideoUrl(body, server.baseUrl)
                if (extractedUrl != null) {
                    Log.d(TAG, "Extracted video URL: $extractedUrl")
                    Result.success(extractedUrl)
                } else {
                    Result.failure(Exception("Could not extract video URL from response"))
                }
            } else {
                response.close()
                Result.failure(Exception("Server returned ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching video: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Download video from URL and cache it
     */
    suspend fun downloadAndCacheVideo(
        videoUrl: String,
        fileName: String = generateFileName(videoUrl),
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = cacheManager.downloadVideoToCache(
                videoUrl = videoUrl,
                fileName = fileName,
                onProgress = onProgress
            )
            
            result.mapCatching { file ->
                file.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading video: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Schedule cache cleanup after video playback ends
     */
    fun scheduleAutoCleanup(delayMs: Long = 2000) {
        cacheManager.scheduleAutoCleanup(delayMs)
    }

    /**
     * Cancel scheduled cleanup
     */
    fun cancelAutoCleanup() {
        cacheManager.cancelAutoCleanup()
    }

    /**
     * Immediately clear cache
     */
    fun clearCache() {
        cacheManager.clearCacheSync()
    }

    /**
     * Get cache size
     */
    fun getCacheSizeFormatted(): String = cacheManager.getCacheSizeFormatted()

    /**
     * Cleanup when done
     */
    fun destroy() {
        cacheManager.destroy()
    }

    // ---- Private Methods ----

    private fun extractVideoUrl(html: String, baseUrl: String): String? {
        // Try to extract m3u8 (HLS)
        val m3u8Regex = "([\"']?)([^\"']*\\.m3u8[^\"']*)\\1".toRegex()
        m3u8Regex.find(html)?.let { match ->
            var url = match.groupValues[2]
            // Make URL absolute if relative
            if (!url.startsWith("http")) {
                url = baseUrl.substringBeforeLast("/") + "/" + url.trimStart('/')
            }
            return url
        }

        // Try to extract mp4
        val mp4Regex = "([\"']?)([^\"']*\\.mp4[^\"']*)\\1".toRegex()
        mp4Regex.find(html)?.let { match ->
            var url = match.groupValues[2]
            if (!url.startsWith("http")) {
                url = baseUrl.substringBeforeLast("/") + "/" + url.trimStart('/')
            }
            return url
        }

        // Try to extract from src attribute
        val srcRegex = """src\s*=\s*[\"']([^\"']+)[\"']""".toRegex()
        srcRegex.find(html)?.let { match ->
            var url = match.groupValues[1]
            if (!url.startsWith("http")) {
                url = baseUrl.substringBeforeLast("/") + "/" + url.trimStart('/')
            }
            return url
        }

        // Try to extract data-src or data attribute
        val dataRegex = """data-?src\s*=\s*[\"']([^\"']+)[\"']""".toRegex()
        dataRegex.find(html)?.let { match ->
            var url = match.groupValues[1]
            if (!url.startsWith("http")) {
                url = baseUrl.substringBeforeLast("/") + "/" + url.trimStart('/')
            }
            return url
        }

        return null
    }

    private fun generateFileName(url: String): String {
        val hash = url.hashCode().toString().replace("-", "")
        val extension = url.substringAfterLast(".").take(4).lowercase()
        return "video_$hash.$extension"
    }

}
