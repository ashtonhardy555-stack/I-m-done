package com.mariocart.app.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.*

@UnstableApi
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    companion object {
        fun newIntent(
            context: Context,
            tmdbId: Int,
            contentType: String = "movie",
            season: Int = 1,
            episode: Int = 1,
            title: String = ""
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra("TMDB_ID", tmdbId)
                putExtra("CONTENT_TYPE", contentType)
                putExtra("SEASON", season)
                putExtra("EPISODE", episode)
                putExtra("TITLE", title)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tmdbId = intent.getIntExtra("TMDB_ID", -1)
        val contentType = intent.getStringExtra("CONTENT_TYPE") ?: "movie"
        val season = intent.getIntExtra("SEASON", 1)
        val episode = intent.getIntExtra("EPISODE", 1)
        val title = intent.getStringExtra("TITLE") ?: "Playing..."

        if (tmdbId == -1) {
            finish()
            return
        }

        setContent {
            MarioCartTheme {
                PlayerScreen(
                    tmdbId = tmdbId,
                    contentType = contentType,
                    season = season,
                    episode = episode,
                    title = title,
                    onPlayerReady = { exoPlayer -> player = exoPlayer }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

@UnstableApi
@Composable
fun PlayerScreen(
    tmdbId: Int,
    contentType: String,
    season: Int,
    episode: Int,
    title: String,
    onPlayerReady: (ExoPlayer) -> Unit
) {
    val context = LocalContext.current
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tmdbId, contentType, season, episode) {
        try {
            val url = StreamExtractor.extract(tmdbId, contentType, season, episode)
            if (url.isNullOrBlank()) {
                error = "No stream found for this content"
            } else {
                streamUrl = url
            }
        } catch (e: Exception) {
            error = "Failed to load stream: ${e.message}"
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator()
            }
            error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(text = error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Text("Go Back")
                    }
                }
            }
            streamUrl != null -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            val exoPlayer = ExoPlayer.Builder(ctx).build().apply {
                                setMediaItem(MediaItem.fromUri(streamUrl!!))
                                prepare()
                                playWhenReady = true
                            }
                            player = exoPlayer
                            useController = true
                            onPlayerReady(exoPlayer)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
