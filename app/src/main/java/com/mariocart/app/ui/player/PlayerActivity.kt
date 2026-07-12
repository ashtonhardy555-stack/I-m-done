package com.mariocart.app.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mariocart.app.ui.theme.MarioCartTheme

class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tmdbId = intent.getIntExtra("tmdbId", -1)
        val contentType = intent.getStringExtra("contentType") ?: "movie"

        setContent {
            MarioCartTheme {
                PlayerScreen(tmdbId = tmdbId, contentType = contentType)
            }
        }
    }

    companion object {
        fun newIntent(context: Context, tmdbId: Int, contentType: String): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra("tmdbId", tmdbId)
                putExtra("contentType", contentType)
            }
        }
    }
}

@Composable
private fun PlayerScreen(tmdbId: Int, contentType: String) {
    // Your existing player UI code here (ExoPlayer, etc.)
    // Example placeholder:
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Playing: $contentType ID $tmdbId", style = MaterialTheme.typography.headlineMedium)
            // Add your video player implementation
        }
    }
}
