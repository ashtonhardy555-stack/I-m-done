package com.mariocart.app.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.server.StreamExtractor
import com.mariocart.app.ui.theme.MarioCartTheme
import kotlinx.coroutines.delay

@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        fun newIntent(
            context: Context,
            tmdbId: Int,
            contentType: String = "movie",
            season: Int = 1,
            episode: Int = 1,
            title: String = "Now Playing"
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("TMDB_ID", tmdbId)
            putExtra("CONTENT_TYPE", contentType)
            putExtra("SEASON", season)
            putExtra("EPISODE", episode)
            putExtra("TITLE", title)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tmdbId = intent.getIntExtra("TMDB_ID", -1)
        val contentType = intent.getStringExtra("CONTENT_TYPE") ?: "movie"
        val season = intent.getIntExtra("SEASON", 1)
        val episode = intent.getIntExtra("EPISODE", 1)

        if (tmdbId == -1) {
            Log.e("PlayerActivity", "Invalid TMDB ID")
            finish()
            return
        }

        setContent {
            MarioCartTheme {
                PlayerScreen(tmdbId, contentType, season, episode)
            }
        }
    }
}

@UnstableApi
@Composable
fun PlayerScreen(
    tmdbId: Int,
    contentType: String,
    season: Int,
    episode: Int
) {
    val localContext = LocalContext.current
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableStateOf(0) }
    val maxRetries = 3

    LaunchedEffect(tmdbId, contentType, season, episode, retryCount) {
        isLoading = true
        error = null

        try {
            Log.d("Player", "🔍 Starting extraction for TMDB $tmdbId ($contentType S$season E$episode)")
            val url = StreamExtractor.extract(tmdbId, contentType, season, episode)
            if (!url.isNullOrBlank()) {
                streamUrl = url
                Log.i("Player", "✅ Stream URL ready")
            } else {
                throw Exception("StreamExtractor returned empty URL")
            }
        } catch (e: Exception) {
            Log.e("Player", "💥 Extraction failed", e)
            if (retryCount < maxRetries) {
                retryCount++
                delay(1500)
            } else {
                error = "Failed to load stream.\n\n${e.message?.take(120) ?: e::class.simpleName}"
            }
        } finally {
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            isLoading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Finding best stream for TMDB $tmdbId...", color = MaterialTheme.colorScheme.onBackground)
                }
            }
            error != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("⚠️ $error", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { (localContext as? ComponentActivity)?.finish() }) {
                        Text("Back")
                    }
                }
            }
            streamUrl != null -> {
                AndroidView(
                    factory = { ctx ->
                        val player = ExoPlayer.Builder(ctx).build().apply {
                            setMediaItem(MediaItem.fromUri(streamUrl!!))
                            prepare()
                            playWhenReady = true
                        }
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
