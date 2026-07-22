package com.ashtonhardy.piratesfilmcove.data.cache

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages temporary video cache - downloads videos from servers/APIs
 * and automatically clears them when playback ends or is closed.
 */
class VideoCacheManager(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "video_cache").apply {
        if (!exists()) mkdirs()
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var currentCacheFile: File? = null
    private var cleanupJob: Job? = null

    init {
        // Clean up any leftover cache files on startup
        clearCache()
    }

    /**
     * Downloads video from URL to temporary cache and returns the local file path
     * @param videoUrl The direct video URL to download
     * @param fileName Optional custom filename (defaults to hash of URL)
     * @param headers Optional HTTP headers for the request
     * @param onProgress Called with (bytesDownloaded, totalBytes)
     */
    fun downloadVideoToCache(
        videoUrl: String,
        fileName: String = generateFileName(videoUrl),
        headers: Map<String, String> = emptyMap(),
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> = runBlocking {
        try {
            val cacheFile = File(cacheDir, fileName)
            
            // If already downloading this file, return it
            if (cacheFile.exists() && currentCacheFile?.absolutePath == cacheFile.absolutePath) {
                return@runBlocking Result.success(cacheFile)
            }

            currentCacheFile = cacheFile
            val downloaded = downloadFile(videoUrl, cacheFile, headers, onProgress)
            
            if (downloaded) {
                Result.success(cacheFile)
            } else {
                cacheFile.delete()
                Result.failure(Exception("Failed to download video"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the cached file path if it exists (without downloading)
     */
    fun getCachedFilePath(fileName: String): File? {
        val file = File(cacheDir, fileName)
        return if (file.exists()) file else null
    }

    /**
     * Clear all temporary cache files
     */
    fun clearCache() {
        scope.launch {
            try {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }
                currentCacheFile = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Clear cache immediately and synchronously
     */
    fun clearCacheSync() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
            currentCacheFile = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Schedule automatic cache cleanup after playback ends
     * @param delayMs Delay before clearing (default 2 seconds after playback ends)
     */
    fun scheduleAutoCleanup(delayMs: Long = 2000) {
        // Cancel any existing cleanup job
        cleanupJob?.cancel()
        
        cleanupJob = scope.launch {
            delay(delayMs)
            clearCache()
        }
    }

    /**
     * Cancel scheduled cleanup (call this if video is paused/resumed)
     */
    fun cancelAutoCleanup() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    /**
     * Get cache size in bytes
     */
    fun getCacheSizeBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Get cache size as formatted string
     */
    fun getCacheSizeFormatted(): String {
        val bytes = getCacheSizeBytes()
        return when {
            bytes <= 0L -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    /**
     * Cleanup when activity is destroyed
     */
    fun destroy() {
        cleanupJob?.cancel()
        scope.cancel()
        clearCacheSync()
    }

    // ---- Private Methods ----

    private suspend fun downloadFile(
        url: String,
        file: File,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"

            // Add custom headers
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // Add user agent to avoid blocks
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
            )

            if (connection.responseCode == 200) {
                val totalBytes = connection.contentLength.toLong()
                val inputStream = connection.inputStream
                val outputStream = file.outputStream()

                inputStream.use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            onProgress(totalRead, totalBytes)
                        }
                    }
                }
                connection.disconnect()
                true
            } else {
                connection.disconnect()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun generateFileName(url: String): String {
        val hash = url.hashCode().toString().replace("-", "")
        val extension = url.substringAfterLast(".").take(4)
        return "video_$hash.$extension"
    }

    companion object {
        private var instance: VideoCacheManager? = null

        fun getInstance(context: Context): VideoCacheManager {
            return instance ?: synchronized(this) {
                VideoCacheManager(context).also { instance = it }
            }
        }
    }
}
