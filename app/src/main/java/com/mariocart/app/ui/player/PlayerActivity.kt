package com.mariocart.app.ui.player

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.data.server.ServerTester
import com.mariocart.app.data.server.StreamExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_TYPE    = "type"
        private const val EXTRA_TITLE   = "title"
        private const val EXTRA_SEASON  = "season"
        private const val EXTRA_EPISODE = "episode"

        private const val MIN_DURATION_MS      = 5 * 60 * 1000L
        private const val EXTRACT_TIMEOUT_MS   = 15_000L
        private const val DIRECT_TIMEOUT_MS    = 20_000L

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

    private var servers: List<StreamingServer> = emptyList()
    private var currentServerIndex = 0
    private var tmdbId = 0
    private var contentType = "movie"
    private var season = 1
    private var episode = 1
    private var title = ""
    private var currentEmbedUrl = ""

    private var extractJob: Job? = null

    private var exoPlayer: ExoPlayer? = null
    private var isPlaying = false
    private var isSeeking = false
    private var userInitiatedPause = false
    private var savedPositionMs = 0L
    private var selectedMaxHeight = Int.MAX_VALUE

    private lateinit var playerView: PlayerView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingTitle: TextView
    private lateinit var loadingStatus: TextView
    private lateinit var loadingDots: TextView
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var qualityBtn: TextView
    private lateinit var errorText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private var dotsRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var dotsCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        if (tmdbId <= 0) { finish(); return }
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        title       = intent.getStringExtra(EXTRA_TITLE) ?: ""
        season      = intent.getIntExtra(EXTRA_SEASON, 1)
        episode     = intent.getIntExtra(EXTRA_EPISODE, 1)

        buildLayout()
        initServersAndPlay()
    }

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setOnClickListener { toggleControlsVisibility() }
        }
        root.addView(playerView)

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val loadingCenter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        }
        loadingTitle = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 24)
            maxLines = 2
        }
        loadingDots = TextView(this).apply {
            text = "⬤  ⬤  ⬤"
            setTextColor(Color.parseColor("#555555"))
            textSize = 14f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
        }
        loadingStatus = TextView(this).apply {
            text = "Finding a stream…"
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(48, 20, 48, 0)
        }
        errorText = TextView(this).apply {
            setTextColor(Color.parseColor("#FF6B6B"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(48, 12, 48, 0)
            visibility = View.GONE
        }
        loadingCenter.addView(loadingTitle)
        loadingCenter.addView(loadingDots)
        loadingCenter.addView(loadingStatus)
        loadingCenter.addView(errorText)
        loadingOverlay.addView(loadingCenter)
        root.addView(loadingOverlay)

        controlsOverlay = buildControlsOverlay()
        root.addView(controlsOverlay)

        setContentView(root)
    }

    private fun buildControlsOverlay(): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        val topScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(80))
        }
        overlay.addView(topScrim)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = -dp(80) }
        }
        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(dp(16), dp(16), dp(8), dp(16))
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)
        titleLabel = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        topBar.addView(titleLabel)
        val sourceBtn = TextView(this).apply {
            text = "SOURCE"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBg(Color.parseColor("#44FFFFFF"), dp(4))
            setOnClickListener { showServerPicker() }
        }
        topBar.addView(sourceBtn)
        overlay.addView(topBar)

        overlay.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        })

        val bottomScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(120))
        }
        overlay.addView(bottomScrim)

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = -dp(120) }
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            progressDrawable?.setTint(Color.WHITE)
            thumb?.setTint(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) exoPlayer?.let { pl ->
                        val dur = pl.duration
                        if (dur > 0) pl.seekTo((p.toLong() * dur) / 1000L)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true; cancelAutoHide() }
                override fun onStopTrackingTouch(sb: SeekBar?) { isSeeking = false; scheduleAutoHide() }
            })
        }
        seekRow.addView(seekBar)
        timeLabel = TextView(this).apply {
            text = "0:00 / 0:00"
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 11f
            setPadding(dp(10), 0, 0, 0)
        }
        seekRow.addView(timeLabel)
        overlay.addView(seekRow)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(20))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val rewindBtn = iconBtn(android.R.drawable.ic_media_rew) { seekRelative(-10_000L) }
        bottomBar.addView(rewindBtn)
        playPauseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                leftMargin = dp(8); rightMargin = dp(8)
            }
            setOnClickListener { togglePlayPause() }
        }
        bottomBar.addView(playPauseBtn)
        val forwardBtn = iconBtn(android.R.drawable.ic_media_ff) { seekRelative(10_000L) }
        bottomBar.addView(forwardBtn)
        bottomBar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        qualityBtn = TextView(this).apply {
            text = "Auto"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBg(Color.parseColor("#44FFFFFF"), dp(4))
            setOnClickListener { showQualityPicker() }
        }
        bottomBar.addView(qualityBtn)
        overlay.addView(bottomBar)

        return overlay
    }

    // ── Initialization — tries direct JSON APIs first, then server scraping ───

    private fun initServersAndPlay() {
        startDotsAnimation()
        extractJob = lifecycleScope.launch {

            // Step 1: try all known JSON APIs directly (fastest path)
            setLoadingStatus("Checking direct sources…")
            val directUrl = withTimeoutOrNull(DIRECT_TIMEOUT_MS) {
                StreamExtractor.extractDirect(tmdbId, contentType, season, episode)
            }
            if (directUrl != null) {
                onVideoUrlFound(directUrl)
                return@launch
            }

            // Step 2: fall back to probing embed servers
            setLoadingStatus("Checking servers…")
            ServerManager.initialize(this@PlayerActivity)
            val raw = ServerManager.getOrderedServers()
            servers = ServerTester.rankForContent(raw, tmdbId, contentType, season, episode)
            loadServer(0)
        }
    }

    private fun loadServer(index: Int) {
        if (index >= servers.size) {
            showError("No working stream found.\nTap SOURCE to pick manually.")
            return
        }
        currentServerIndex = index
        extractJob?.cancel()
        releaseExoPlayer()

        val server = servers[index]
        currentEmbedUrl = if (contentType == "movie") server.movieUrl(tmdbId)
                          else server.tvUrl(tmdbId, season, episode)

        setLoadingStatus("Trying ${server.name}…")
        hideError()

        extractJob = lifecycleScope.launch {
            val videoUrl = withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                StreamExtractor.extract(currentEmbedUrl, tmdbId, contentType, season, episode)
            }
            if (videoUrl != null) {
                onVideoUrlFound(videoUrl)
            } else {
                ServerManager.markServerDead(server.name)
                tryNextServer()
            }
        }
    }

    private fun tryNextServer() {
        val next = currentServerIndex + 1
        if (next < servers.size) loadServer(next)
        else showError("All sources tried.\nTap SOURCE to pick manually.")
    }

    private fun onVideoUrlFound(videoUrl: String) {
        ServerManager.markServerSuccess(servers.getOrNull(currentServerIndex)?.name ?: "")

        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        playerView.player = player

        val embedHost = try { "https://${Uri.parse(currentEmbedUrl).host}" } catch (_: Exception) { "" }
        val httpDsf = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to currentEmbedUrl, "Origin" to embedHost))

        val mi = MediaItem.fromUri(videoUrl)
        val source = if (videoUrl.lowercase().let { it.contains(".m3u8") || it.contains("/hls/") })
            HlsMediaSource.Factory(httpDsf).createMediaSource(mi)
        else
            ProgressiveMediaSource.Factory(httpDsf).createMediaSource(mi)

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true

        if (selectedMaxHeight != Int.MAX_VALUE) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon().setMaxVideoSize(Int.MAX_VALUE, selectedMaxHeight).build()
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        val dur = player.duration
                        if (dur in 1L until MIN_DURATION_MS) {
                            setLoadingStatus("Clip too short, trying next…")
                            showLoadingOverlay()
                            releaseExoPlayer()
                            handler.postDelayed({ tryNextServer() }, 300L)
                            return
                        }
                        isPlaying = true
                        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                        showPlayerControls()
                        startProgressUpdater()
                        resumeFromSavedPosition()
                    }
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isPlaying = false
                savedPositionMs = exoPlayer?.currentPosition ?: 0L
                releaseExoPlayer()
                setLoadingStatus("Source failed, trying next…")
                showLoadingOverlay()
                handler.postDelayed({ tryNextServer() }, 500L)
            }
        })
    }

    private fun showPlayerControls() {
        loadingOverlay.visibility = View.GONE
        controlsOverlay.visibility = View.VISIBLE
        scheduleAutoHide()
    }

    private fun showLoadingOverlay() {
        controlsOverlay.visibility = View.GONE
        loadingOverlay.visibility = View.VISIBLE
        startDotsAnimation()
    }

    private fun toggleControlsVisibility() {
        if (controlsOverlay.visibility == View.VISIBLE) {
            controlsOverlay.visibility = View.GONE
            cancelAutoHide()
        } else {
            controlsOverlay.visibility = View.VISIBLE
            scheduleAutoHide()
        }
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        autoHideRunnable = Runnable { controlsOverlay.visibility = View.GONE }
        handler.postDelayed(autoHideRunnable!!, 3_500L)
    }

    private fun cancelAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause(); userInitiatedPause = true; isPlaying = false
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
        } else {
            player.play(); userInitiatedPause = false; isPlaying = true
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
        }
        scheduleAutoHide()
    }

    private fun seekRelative(offsetMs: Long) {
        exoPlayer?.let { pl ->
            pl.seekTo((pl.currentPosition + offsetMs).coerceAtLeast(0L))
            scheduleAutoHide()
        }
    }

    private fun startProgressUpdater() {
        progressRunnable = object : Runnable {
            override fun run() {
                val pl = exoPlayer ?: return
                val pos = pl.currentPosition
                val dur = pl.duration
                if (!isSeeking && dur > 0) {
                    seekBar.progress = ((pos * 1000L) / dur).toInt()
                    timeLabel.text = "${formatMs(pos)} / ${formatMs(dur)}"
                }
                handler.postDelayed(this, 500L)
            }
        }
        handler.postDelayed(progressRunnable!!, 500L)
    }

    private fun stopProgressUpdater() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun resumeFromSavedPosition() {
        val pos = savedPositionMs; savedPositionMs = 0L
        if (pos > 3_000L) handler.postDelayed({ exoPlayer?.seekTo(pos) }, 600L)
    }

    private fun releaseExoPlayer() {
        stopProgressUpdater()
        exoPlayer?.release(); exoPlayer = null
        isPlaying = false
    }

    private fun showQualityPicker() {
        cancelAutoHide()
        val labels  = arrayOf("Auto (Best)", "1080p", "720p", "480p", "360p")
        val heights = intArrayOf(Int.MAX_VALUE, 1080, 720, 480, 360)
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        dialog.setContentView(buildPickerDialog("Quality", labels) { idx ->
            selectedMaxHeight = heights[idx]
            qualityBtn.text = labels[idx]
            exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
                ?.buildUpon()?.setMaxVideoSize(Int.MAX_VALUE, heights[idx])?.build()
                ?: return@buildPickerDialog
            dialog.dismiss(); scheduleAutoHide()
        })
        dialog.show()
    }

    private fun showServerPicker() {
        cancelAutoHide()
        val names = servers.mapIndexed { i, s ->
            "${when { i == currentServerIndex -> "▶  "; i < currentServerIndex -> "✓  "; else -> "     " }}${s.name}"
        }.toTypedArray()
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        dialog.setContentView(buildPickerDialog("Select Source", names) { idx ->
            if (idx != currentServerIndex) {
                savedPositionMs = exoPlayer?.currentPosition ?: 0L
                showLoadingOverlay(); loadServer(idx)
            }
            dialog.dismiss()
        })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { if (isPlaying) scheduleAutoHide() }
        dialog.show()
    }

    private fun buildPickerDialog(title: String, items: Array<String>, onPick: (Int) -> Unit): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#1E1E1E"), dp(12))
            setPadding(0, dp(16), 0, dp(8))
            minimumWidth = dp(280)
        }
        wrapper.addView(TextView(this).apply {
            text = title
            setText **...**

_This response is too long to display in full._
