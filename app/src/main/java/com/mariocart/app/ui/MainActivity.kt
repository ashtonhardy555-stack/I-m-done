// app/src/main/java/com/mariocart/app/ui/player/PlayerActivity.kt
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

@UnstableApi
class PlayerActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null

    companion object {
        private const val TAG = "PlayerActivity"

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
                PlayerScreen(title, tmdbId, contentType, season, episode)
            }
        }
    }

    @Composable
    private fun PlayerScreen(
        title: String,
        tmdbId: Int,
        contentType: String,
        season: Int,
        episode: Int
    ) {
        var isLoading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current
        var player by remember { mutableStateOf<ExoPlayer?>(null) }

        LaunchedEffect(tmdbId) {
            try {
                val url = StreamExtractor.extract(tmdbId, contentType, season, episode)
                if (url.isNullOrBlank()) {
                    error = "No stream found"
                } else {
                    player = ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(url))
                        prepare()
                        playWhenReady = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed", e)
                error = e.message ?: "Playback error"
            } finally {
                isLoading = false
            }
        }

        DisposableEffect(Unit) { onDispose { player?.release() } }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            player?.let {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = it; useController = true } },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            if (error != null) {
                Column(Modifier.fillMaxSize().padding(32.dp), Alignment.CenterHorizontally, Arrangement.Center) {
                    Text("Error", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                    Text(error!!)
                    Button(onClick = { finish() }) { Text("Back") }
                }
            }

            // Top bar
            Row(Modifier.fillMaxWidth().padding(16.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Text("←", fontSize = 24.sp, color = Color.White) }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
            }
        }
    }

    override fun onPause() { super.onPause(); exoPlayer?.playWhenReady = false }
    override fun onDestroy() { super.onDestroy(); exoPlayer?.release() }
}
