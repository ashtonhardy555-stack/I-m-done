package com.ashtonhardy.piratesfilmcove.data.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * KodiEngineService \u2014 a foreground [Service] that keeps the [KodiEngine]
 * (the "Kodi-like background engine" running the LookMovieTomb addon flow)
 * alive while the app is in use.
 *
 * ## Why a foreground service?
 *
 * The user asked for the app to "run a Kodi-like engine in the background"
 * pulling movies via the LookMovieTomb addon. On modern Android, a long-lived
 * background coroutine scope is liable to be killed by the system once the
 * app goes to the background unless it's backed by a foreground service with
 * a persistent notification. This service provides that backing: it starts
 * the engine in `START_STICKY` mode and shows a small, non-intrusive
 * notification so the OS keeps the engine's pre-resolve work alive.
 *
 * ## What it does NOT do
 *
 *  \u2022 It does **not** use a WebView. The engine is pure headless OkHttp
 *    (see [KodiEngine] / [LookMovieHeadlessExtractor]).
 *  \u2022 It does **not** do any playback itself \u2014 ExoPlayer still plays in
 *    [com.ashtonhardy.piratesfilmcove.ui.player.PlayerActivity]. The service only keeps the
 *    resolver warm and pre-resolving.
 *
 * ## Lifecycle
 *
 *  \u2022 Started from [com.ashtonhardy.piratesfilmcove.PiratesfilmCoveApplication] (or
 *    MainActivity) at app launch.
 *  \u2022 `START_STICKY` so Android restarts it if memory pressure kills it.
 *  \u2500 stops cleanly when the app is destroyed / user dismisses.
 */
class KodiEngineService : Service() {

    private val TAG = "KodiEngineService"

    companion object {
        const val CHANNEL_ID = "piratesfilmcove_kodi_engine"
        const val NOTIFICATION_ID = 0xD0D0 // stable id

        const val ACTION_START = "com.ashtonhardy.piratesfilmcove.engine.START"
        const val ACTION_STOP = "com.ashtonhardy.piratesfilmcove.engine.STOP"

        /** Convenience to start the engine service. */
        fun start(context: Context) {
            val intent = Intent(context, KodiEngineService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Convenience to stop the engine service. */
        fun stop(context: Context) {
            val intent = Intent(context, KodiEngineService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                KodiEngine.get().stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // START or null action \u2014 keep the engine running.
                startForeground(NOTIFICATION_ID, buildNotification())
                KodiEngine.get().start()
                Log.i(TAG, "foreground service started, engine running")
            }
        }
        // Keep the engine alive across memory-pressure kills.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        runCatching { KodiEngine.get().stop() }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away, stop the engine + service so we
        // don't leave a lingering notification with no app context.
        Log.i(TAG, "task removed \u2014 stopping engine")
        runCatching { KodiEngine.get().stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // \u2500\u2500 notification \u2500\u2500

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stream Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background movie source engine (LookMovie)"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val cached = runCatching { KodiEngine.get().cacheSize() }.getOrDefault(0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Netflix engine")
            .setContentText(
                if (cached > 0) "Sources ready \u2014 $cached title(s) pre-resolved"
                else "Finding movie sources in the background\u2026"
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
