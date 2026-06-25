package com.mariocart.app.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.server.StreamExtractor
import com.mariocart.app.ui.theme.MarioCartTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                    onPlayClick = { playStream(tmdbId, contentType, season, episode) },
                    onBackClick = { finish() }
                )
            }
        }
    }

    private fun playStream(tmdbId: Int, contentType: String, season: Int, episode: Int) {
        scope.launch {
            try {
                val streamUrl = StreamExtractor.extract(
                    tmdbId = tmdbId,
                    contentType = contentType,
                    season = season,
                    episode = episode
                )

                if (streamUrl.isNullOrEmpty()) {
                    Toast.makeText(this@PlayerActivity, "Failed to extract stream", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.i("PlayerActivity", "✅ Playing: $streamUrl")
                initializePlayer(streamUrl)
            } catch (e: Exception) {
                Log.e("PlayerActivity", "Playback error", e)
                Toast.makeText(this@PlayerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initializePlayer(url: String) {
        exoPlayer?.release()

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.Builder().setUri(url).build()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
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

// Simple Player UI
@Composable
fun PlayerScreen(
    title: String,
    onPlayClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(48.dp))

        Button(onClick = onPlayClick, modifier = Modifier.fillMaxWidth(0.8f)) {
            Text("▶ Play Video")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onBackClick, modifier = Modifier.fillMaxWidth(0.8f)) {
            Text("Back")
        }
    }
}
