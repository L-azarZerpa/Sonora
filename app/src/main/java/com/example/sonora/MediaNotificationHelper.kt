package com.example.sonora

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

object MediaNotificationHelper {
    private const val CHANNEL_ID = "sonora_media_player_v8"
    private const val NOTIFICATION_ID = 2001

    private var mediaSession: MediaSessionCompat? = null

    private fun getMediaSession(context: Context): MediaSessionCompat {
        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(context.applicationContext, "SonoraMediaSession").apply {
                isActive = true
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        MainActivity.instance?.runOnUiThread {
                            MainActivity.instance?.togglePlayPause()
                        }
                    }

                    override fun onPause() {
                        MainActivity.instance?.runOnUiThread {
                            MainActivity.instance?.togglePlayPause()
                        }
                    }

                    override fun onSkipToNext() {
                        MainActivity.instance?.runOnUiThread {
                            MainActivity.instance?.playNextSong()
                        }
                    }

                    override fun onSkipToPrevious() {
                        MainActivity.instance?.runOnUiThread {
                            MainActivity.instance?.playPreviousSong()
                        }
                    }

                    override fun onFastForward() {
                        MainActivity.instance?.runOnUiThread {
                            MainActivity.instance?.forward5Seconds()
                        }
                    }

                    override fun onRewind() {
                        MainActivity.instance?.runOnUiThread {
                            MainActivity.instance?.rewind5Seconds()
                        }
                    }

                    override fun onSeekTo(pos: Long) {
                        MainActivity.instance?.runOnUiThread {
                            val activity = MainActivity.instance ?: return@runOnUiThread
                            val player = activity.mediaPlayer
                            if (player != null && activity.currentPlayingSong != null) {
                                val targetPos = pos.toInt()
                                player.seekTo(targetPos)
                                activity.saveLastPlayedSong(activity.currentPlayingSong!!, targetPos)
                                activity.showMediaNotification()
                            }
                        }
                    }
                })
            }
        }
        return mediaSession!!
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproductor Sonora",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Controles de reproducción para Sonora"
                setShowBadge(false)
                enableVibration(false)
                vibrationPattern = longArrayOf(0L)
                setSound(null, null)
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun showMediaNotification(
        context: Context,
        song: Song?,
        isPlaying: Boolean,
        currentPosMs: Int = 0
    ) {
        if (song == null) return

        val session = getMediaSession(context)

        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                currentPosMs.toLong(),
                1.0f
            )
            .build()
        session.setPlaybackState(state)

        val artBitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.parseColor("#0F0F12"))
        }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artBitmap)
            .build()
        session.setMetadata(metadata)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MusicControlReceiver::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val prevIntent = createActionPendingIntent("ACTION_PREV", 1)
        val rewindIntent = createActionPendingIntent("ACTION_REWIND_5", 2)
        val playPauseIntent = createActionPendingIntent("ACTION_PLAY_PAUSE", 3)
        val forwardIntent = createActionPendingIntent("ACTION_FORWARD_5", 4)
        val nextIntent = createActionPendingIntent("ACTION_NEXT", 5)

        val pinkColor = Color.parseColor("#F7CDDA")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(formatDuration(song.duration))
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setColor(pinkColor)
            .setColorized(true)
            .setVibrate(longArrayOf(0L))
            .setSound(null)
            .setDefaults(0)
            .addAction(R.drawable.ic_skip_previous, "Anterior", prevIntent)
            .addAction(R.drawable.ic_fast_rewind, "-5s", rewindIntent)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pausa" else "Play",
                playPauseIntent,
            )
            .addAction(R.drawable.ic_fast_forward, "+5s", forwardIntent)
            .addAction(R.drawable.ic_skip_next, "Siguiente", nextIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2, 3, 4),
            )

        val notification = builder.build()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    fun cancelMediaNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(NOTIFICATION_ID)
        mediaSession?.release()
        mediaSession = null
    }
}
