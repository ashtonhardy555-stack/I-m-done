package com.mariocart.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.ui.home.HomeScreen
import com.mariocart.app.ui.player.PlayerActivity
import com.mariocart.app.ui.theme.MarioCartTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load the server list (servers.json) and the persistent success-score
        // store once at launch so the player has an ordered, health-tracked
        // server list ready before the first playback request.
        ServerManager.initialize(this)

        setContent {
            MarioCartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        onItemClick = { item: TmdbItem ->
                            val contentType = if (item.mediaType == "tv" || item.isMovie == false) "tv" else "movie"

                            // Pass the title so the player can show it, and
                            // forward season/episode (TV only — defaults to
                            // 1/1 which the player UI will let the user change
                            // later).  Title is used for display + logging.
                            val intent = PlayerActivity.newIntent(
                                context = this,
                                tmdbId = item.id,
                                type = contentType,
                                title = item.displayTitle,
                                season = 1,
                                episode = 1
                            )
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}
