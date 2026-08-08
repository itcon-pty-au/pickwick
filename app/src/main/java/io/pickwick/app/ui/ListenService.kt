package io.pickwick.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager

/**
 * The background half of listen mode: while the phone screen is off, this
 * foreground service is what keeps the process (and so the audio) alive.
 * The player itself stays owned by [PlayerActivity], which survives its own
 * onStop — the static handoff mirrors [io.pickwick.app.data.RemotePlayerControl].
 * The media notification puts a pause button on the lock screen, and the
 * [MediaSession] behind it routes headset buttons too. Phones only by
 * construction: the sole caller is PlayerActivity.onStop with the display
 * actually off, a state a TV never reaches while the app runs.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ListenService : Service() {

    companion object {
        /** Set by PlayerActivity right before start(); cleared in its onDestroy. */
        var player: ExoPlayer? = null
        var title: String = ""
        var channelName: String = ""

        private const val NOTIFICATION_ID = 41
        private const val CHANNEL_ID = "listening"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ListenService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }

    private var session: MediaSession? = null
    private var notifications: PlayerNotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Playback while the screen is off" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val exo = player
        if (exo == null) {
            // Restarted by the system with no live player to adopt — nothing to do.
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground immediately (the post-start window is short and an ANR
        // here kills playback); a bare notification first, replaced in place —
        // same id — by PlayerNotificationManager's media-style one below.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.ifBlank { "Listening" })
                .setOnlyAlertOnce(true)
                .build(),
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        if (session == null) {
            val s = MediaSession.Builder(this, exo).build()
            session = s
            notifications = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
                .setMediaDescriptionAdapter(object :
                    PlayerNotificationManager.MediaDescriptionAdapter {
                    override fun getCurrentContentTitle(player: Player): CharSequence =
                        title.ifBlank { "Pickwick" }

                    // No tap-through: the player screen is already the top of its
                    // task and comes back on unlock; a fresh launch intent here
                    // would only stack a second, queue-less player on top of it.
                    override fun createCurrentContentIntent(player: Player): PendingIntent? = null

                    override fun getCurrentContentText(player: Player): CharSequence? =
                        channelName.ifBlank { null }

                    override fun getCurrentLargeIcon(
                        player: Player,
                        callback: PlayerNotificationManager.BitmapCallback
                    ): Bitmap? = null
                })
                // Swiping the notification away (possible once paused) is the
                // kid saying "done" — without this the foreground service
                // lingers, player paused, until the next unlock.
                .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                    override fun onNotificationCancelled(
                        notificationId: Int,
                        dismissedByUser: Boolean
                    ) {
                        stopSelf()
                    }
                })
                .build()
                .apply {
                    setMediaSessionToken(s.sessionCompatToken)
                    // Listen mode is "keep this playing", not a browser: pause is
                    // the only decision the lock screen needs to offer.
                    setUseNextAction(false)
                    setUsePreviousAction(false)
                    setUseStopAction(false)
                    setSmallIcon(android.R.drawable.ic_media_play)
                    setPlayer(exo)
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notifications?.setPlayer(null)
        notifications = null
        session?.release()
        session = null
        super.onDestroy()
    }
}
