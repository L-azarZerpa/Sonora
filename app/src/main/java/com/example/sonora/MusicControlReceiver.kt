package com.example.sonora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MusicControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val activity = MainActivity.instance ?: return
        when (intent.action) {
            "ACTION_PREV" -> activity.playPreviousSong()
            "ACTION_REWIND_5" -> activity.rewind5Seconds()
            "ACTION_PLAY_PAUSE" -> activity.togglePlayPause()
            "ACTION_FORWARD_5" -> activity.forward5Seconds()
            "ACTION_NEXT" -> activity.playNextSong()
        }
    }
}
