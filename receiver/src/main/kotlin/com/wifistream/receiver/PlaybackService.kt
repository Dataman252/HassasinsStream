package com.wifistream.receiver

import android.content.Context
import android.content.Intent
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** What the activity needs to know about the player on screen. */
object PlayerSession {
    /** True while a video plays on a phone, so leaving the app can shrink it into a floating window. */
    @Volatile
    var pipAllowed = false
    var pipAspect: Rational = Rational(16, 9)

    /** True while the app is shown as a small floating window. */
    var inPip by mutableStateOf(false)
}

/**
 * Keeps music playing with the screen off or another app open, and puts its controls on the lock
 * screen and in the notification shade. The player itself lives in the player screen; this service
 * only carries its session.
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        current?.let { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = current

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away stops the music.
        current?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: PlaybackService? = null
        private var current: MediaSession? = null

        /** Gives [player] a session with lock-screen controls. Returns it so the caller can release it. */
        fun attach(context: Context, player: Player): MediaSession {
            detach(context)
            val session = MediaSession.Builder(context, player).setId("hassasin-music").build()
            current = session
            val running = instance
            if (running != null) running.addSession(session) else runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
            return session
        }

        fun detach(context: Context) {
            current?.let { session ->
                instance?.removeSession(session)
                session.release()
            }
            current = null
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
