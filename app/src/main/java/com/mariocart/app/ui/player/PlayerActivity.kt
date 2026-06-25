package com.mariocart.app.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.server.StreamExtractor
import com.mariocart.app.ui.theme.MarioCartTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        const val EXTRA_TMDB_ID = "tmdb_id"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_TITLE = "title"

        fun newIntent(
            context: Context,
            tmdbId: Int,
            contentType: String = "movie",
            season: Int = 1,
            episode: Int = 1,
            title: String = "Now Playing"
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_TMDB_ID, tmdbId)
                putExtra(EXTRA_CONTENT_TYPE, contentType)
                putExtra(EXTRA_SEASON, season)
                putExtra(EXTRA_EPISODE, episode)
                putExtra(EXTRA_TITLE, title)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1)
        val contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: "movie"
        val season = intent.getIntExtra(EXTRA_SEASON, 1)
        val episode = intent.getIntExtra(EXTRA_EPISODE, 1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Playing"

        if (tmdbId == -1) {
            Toast.makeText(this, "Invalid content", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            MarioCartTheme {
                PlayerScreen(
                    title = title,
                    tmdbId = tmdbId,
                    contentType = contentType,
                    season = season,
                    episode = episode
                )
            }
        }
    }

    @Composable
    fun PlayerScreen(
        title: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ) {
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current

        var player by remember { mutableStateOf<ExoPlayer?>(null) }

        DisposableEffect(Unit) {
            onDispose {
                player?.release()
            }
        }

        LaunchedEffect(tmdbId) {
            try {
                isLoading = true
                errorMessage = null

                val streamUrl = StreamExtractor.extract(
                    tmdbId = tmdbId,
                    contentType = contentType,
                    season = season,
                    episode = episode
                )

                if (streamUrl.isNullOrEmpty()) {
                    errorMessage = "Failed to extract stream URL"
                    isLoading = false
                    return@LaunchedEffect
                }

                Log.i("PlayerActivity", "✅ Playing: $streamUrl")

                val exo = ExoPlayer.Builder(context).build().apply {
                    val mediaItem = MediaItem.Builder().setUri(streamUrl).build()
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                }

                player = exo
                isLoading = false
            } catch (e: Exception) {
                Log.e("PlayerActivity", "Error", e)
                errorMessage = e.message ?: "Playback error"
                isLoading = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (player != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Loading
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            // Error
            if (errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Playback Error", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage ?: "", color = MaterialTheme.colorScheme.onBackground)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { finish() }) {
                        Text("Back")
                    }
                }
            }

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { finish() }) {
                    Text("←", fontSize = 24.sp, color = Color.White)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}
