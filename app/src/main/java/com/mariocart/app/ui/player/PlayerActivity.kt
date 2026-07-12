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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
            if (!url.isNullOrBlank() && (url.contains(".m3u8") || url.contains(".mp4"))) {
                streamUrl = url
                Log.i("Player", "✅ Direct playable URL: $url")
            } else {
                throw Exception("No direct video URL found")
            }
        } catch (e: Exception) {
            Log.e("Player", "💥 Extraction failed", e)
            if (retryCount < maxRetries) {
                retryCount++
                delay(2000)
            } else {
                error = "Failed to load stream.\n\nTry another title."
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
                    Text("Finding best stream...", color = MaterialTheme.colorScheme.onBackground)
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
                var player: ExoPlayer? by remember { mutableStateOf(null) }

                AndroidView(
                    factory = { ctx ->
                        val exoPlayer = ExoPlayer.Builder(ctx).build().apply {
                            addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) {
                                    Log.d("ExoPlayer", "State: $state")
                                }

                                override fun onPlayerError(error: PlaybackException) {
                                    Log.e("ExoPlayer", "Error: ${error.errorCodeName} - ${error.message}")
                                }
                            })
                            setMediaItem(MediaItem.fromUri(streamUrl!!))
                            prepare()
                            playWhenReady = true
                        }
                        player = exoPlayer

                        PlayerView(ctx).apply {
                            this.player = exoPlayer
                            useController = true
                            controllerShowTimeoutMs = 3000
                        }
                    },
                    update = {},
                    onRelease = { player?.release() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
