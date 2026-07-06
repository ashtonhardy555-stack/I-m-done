package com.mariocart.app.ui.player

import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Test module to verify streaming works with real titles
 * Tests: "Holes" (2003) - TMDB ID: 8364
 */
object StreamingTest {
    
    /**
     * Test basic stream fetching for popular movies
     */
    fun testStreamFetching() {
        Log.d("StreamTest", "🧪 Starting streaming tests...")

        // Test 1: Holes (2003) - Family movie, widely available
        testTitle(
            tmdbId = 8364,
            title = "Holes",
            contentType = "movie"
        )

        // Test 2: The Matrix (1999) - Classic, high availability
        testTitle(
            tmdbId = 603,
            title = "The Matrix",
            contentType = "movie"
        )

        // Test 3: Breaking Bad S01E01 - TV series test
        testTitle(
            tmdbId = 1396,
            title = "Breaking Bad",
            contentType = "tv",
            season = 1,
            episode = 1
        )
    }

    private fun testTitle(
        tmdbId: Int,
        title: String,
        contentType: String = "movie",
        season: Int = 1,
        episode: Int = 1
    ) {
        Log.i(
            "StreamTest",
            "🎬 Testing: $title (TMDB: $tmdbId, Type: $contentType)"
        )

        try {
            // This would be called with actual context in a real test
            // For now, log the test parameters
            Log.i(
                "StreamTest",
                "✅ Test queued for: $title ($contentType S${season}E${episode})"
            )
        } catch (e: Exception) {
            Log.e("StreamTest", "❌ Test failed for $title: ${e.message}")
        }
    }
}

/**
 * Debug helper to test stream URLs directly
 */
object StreamDebugger {
    
    fun logStreamInfo(
        tmdbId: Int,
        contentType: String,
        streamUrl: String?,
        duration: Long
    ) {
        val status = if (streamUrl != null) "✅ SUCCESS" else "❌ FAILED"
        
        Log.i(
            "StreamDebug",
            """
            $status - Stream Test Result
            TMDB ID: $tmdbId
            Type: $contentType
            Stream URL: ${streamUrl ?: "NOT FOUND"}
            Time: ${duration}ms
            """.trimIndent()
        )
    }
}
