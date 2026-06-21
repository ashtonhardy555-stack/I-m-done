package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.api.ApiClient
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.ServerManager
// import com.mariocart.app.data.server.ServerTester
import com.mariocart.app.data.server.StreamExtractor
import kotlinx.coroutines.launch

/**
 * PlayerActivity — 100% Native Player.
 * 
 * This version COMPLETELY REMOVES WebView. It uses the Advanced Resolver and 
 * StreamExtractor to find direct video files (.m3u8/.mp4) and plays them natively.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_TYPE    = "type"
        private const val EXTRA_TITLE   = "title"
        private const val EXTRA_SEASON  = "season"
        private const val EXTRA_EPISODE = "episode"

        fun newIntent(
            context: Context,
            tmdbId: Int,
            type: String,
            title: String,
            season: Int = 1,
            episode: Int = 1
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_TMDB_ID, tmdbId)
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SEASON, season)
            putExtra(EXTRA_EPISODE, episode)
        }
    }

    private var tmdbId      = 0
    private var contentType = "movie"
    private var season      = 1
    private var episode     = 1
    private var videoTitle  = ""

    private lateinit var playerView:    PlayerView
    private var exoPlayer:    ExoPlayer? = null
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingText:   TextView
    private lateinit var serverButton:  TextView
    
    private var currentServerIndex = -1
    private var autoTryServers = true
    private var discoveryJob: kotlinx.coroutines.Job? = null
    private var currentServerName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        tmdbId      = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        videoTitle  = intent.getStringExtra(EXTRA_TITLE) ?: ""
        season      = intent.getIntExtra(EXTRA_SEASON, 1)
        episode     = intent.getIntExtra(EXTRA_EPISODE, 1)

        setupLayout()
        ServerManager.initialize(this)
        ServerManager.resetHealth()
        startDiscovery()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun setupLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            useController = true
        }

        loadingText = TextView(this).apply {
            text = "Initializing native playback…"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 18f
        }

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(loadingText)
        }

        serverButton = TextView(this).apply {
            text = "Switch Server"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(40, 20, 40, 20)
            textSize = 14f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 60
                rightMargin = 60
            }
            setOnClickListener { showServerSelection() }
        }

        root.addView(playerView)
        root.addView(loadingOverlay)
        root.addView(serverButton)
        setContentView(root)
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = lifecycleScope.launch {
            try {
                loadingText.text = "Resolving direct stream for $videoTitle..."
                val response = ApiClient.streamingBackendApi.getStream(tmdbId, contentType, season, episode)
                
                if (response.success) {
                    if (response.challengeUrl != null) {
                        showChallengeDialog(response.challengeUrl)
                        return@launch
                    }

                    if (response.url != null) {
                        if (response.isDirect == true || response.url.contains(".m3u8") || response.url.contains(".mp4")) {
                            playNative(response.url)
                        } else {
                            // If backend gave an embed, try to extract it natively
                            loadingText.text = "Extracting video from ${response.serverId}..."
                            val directUrl = StreamExtractor.extract(response.url, tmdbId, contentType, season, episode)
                            val localChallenge = StreamExtractor.getLastChallengeUrl()
                            
                            if (directUrl != null) {
                                playNative(directUrl)
                            } else if (localChallenge != null) {
                                showChallengeDialog(localChallenge)
                            } else {
                                tryNextServer()
                            }
                        }
                    } else {
                        tryNextServer()
                    }
                } else {
                    tryNextServer()
                }
            } catch (e: Exception) {
                Log.e("PlayerActivity", "Discovery Error: ${e.message}")
                tryNextServer()
            }
        }
    }

    private fun showChallengeDialog(challengeUrl: String) {
        runOnUiThread {
            loadingOverlay.visibility = View.VISIBLE
            loadingText.text = "Challenge required to access stream."
            
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Verification Required")
                .setMessage("A security challenge (CAPTCHA) is required to continue. Please solve it to access the video.")
                .setPositiveButton("Solve Now") { _, _ ->
                    startActivityForResult(VerificationActivity.newIntent(this, challengeUrl), 1001)
                }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            // User solved CAPTCHA, restart discovery
            loadingOverlay.visibility = View.VISIBLE
            loadingText.text = "Challenge solved! Retrying..."
            startDiscovery()
        } else if (requestCode == 1001) {
            showError("Verification failed or cancelled.")
        }
    }

    private fun tryNextServer() {
        if (!autoTryServers) return
        
        lifecycleScope.launch {
            val allServers = ServerManager.getOrderedServers()
            val servers = allServers
            
            continueWithServers(servers)
        }
    }

    private fun continueWithServers(servers: List<StreamingServer>) {
        currentServerIndex++
        if (currentServerIndex < servers.size) {
            val server = servers[currentServerIndex]
            loadingText.text = "Extracting from ${server.name}..."
            
            lifecycleScope.launch {
                val embedUrl = if (contentType == "movie") server.movieUrl(tmdbId) 
                              else server.tvUrl(tmdbId, season, episode)
                
                val directUrl = StreamExtractor.extract(embedUrl, tmdbId, contentType, season, episode)
                val localChallenge = StreamExtractor.getLastChallengeUrl()
                
                if (directUrl != null) {
                    currentServerName = server.name
                    ServerManager.markServerSuccess(server.name)
                    playNative(directUrl)
                } else if (localChallenge != null) {
                    showChallengeDialog(localChallenge)
                } else {
                    ServerManager.markServerDead(server.name)
                    if (autoTryServers) {
                        continueWithServers(servers)
                    } else {
                        showError("Could not extract video from ${server.name}")
                    }
                }
            }
        } else {
            showError("No native streams found for this title.")
        }
    }

    private fun playNative(url: String) {
        runOnUiThread {
            loadingOverlay.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            serverButton.visibility = View.VISIBLE
            
            if (exoPlayer != null) {
                exoPlayer?.release()
            }
            
            exoPlayer = ExoPlayer.Builder(this).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("PlayerActivity", "Native Playback Error: ${error.message}")
                        showError("Playback failed. Try another server.")
                    }
                })
            }
            playerView.player = exoPlayer
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            loadingOverlay.visibility = View.VISIBLE
            loadingText.text = message
            serverButton.visibility = View.VISIBLE
            showServerSelection()
        }
    }

    private fun showServerSelection() {
        autoTryServers = false
        val servers = ServerManager.getOrderedServers()
        val serverNames = servers.map { it.name }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Native Source")
            .setItems(serverNames) { _, which ->
                currentServerIndex = which
                val server = servers[which]
                loadingOverlay.visibility = View.VISIBLE
                loadingText.text = "Attempting native extraction from ${server.name}..."
                
                lifecycleScope.launch {
                    val embedUrl = if (contentType == "movie") server.movieUrl(tmdbId) 
                                  else server.tvUrl(tmdbId, season, episode)
                    val directUrl = StreamExtractor.extract(embedUrl, tmdbId, contentType, season, episode)
                    val localChallenge = StreamExtractor.getLastChallengeUrl()
                    
                    if (directUrl != null) {
                        playNative(directUrl)
                    } else if (localChallenge != null) {
                        showChallengeDialog(localChallenge)
                    } else {
                        showError("Failed to extract from ${server.name}")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
}
