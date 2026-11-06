package com.example.sonora

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat

class SongListAdapter(
    context: Context,
    songs: List<Song>,
    private val getCurrentPlayingSongId: () -> Long?,
    private val onSongClick: (Song) -> Unit,
    private val onSongOptionsClick: (Song) -> Unit
) : ArrayAdapter<Song>(context, R.layout.item_song, R.id.tvSongTitle, songs) {

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val rowView = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_song, parent, false)
        val song = getItem(pos)

        val textTitle = rowView.findViewById<TextView>(R.id.tvSongTitle)
        val textDetails = rowView.findViewById<TextView>(R.id.tvSongArtistAndDuration)
        val btnOptions = rowView.findViewById<ImageButton>(R.id.btnSongOptions)

        textTitle.text = song?.title
        val durationFormatted = song?.let { formatDuration(it.duration) } ?: "00:00"
        textDetails.text = "${song?.artist} • $durationFormatted"

        btnOptions?.setOnClickListener {
            song?.let { s -> onSongOptionsClick(s) }
        }

        rowView.setOnClickListener {
            song?.let { s -> onSongClick(s) }
        }

        val currentPlayingSongId = getCurrentPlayingSongId()
        val isSelected = song != null && currentPlayingSongId != null && song.id == currentPlayingSongId
        val pinkColor = Color.parseColor("#F7CDDA")

        if (isSelected) {
            rowView.background = ContextCompat.getDrawable(context, R.drawable.bg_song_item_selected)
            textTitle.setTextColor(pinkColor)
            textDetails.setTextColor(pinkColor)
            btnOptions?.setColorFilter(pinkColor)
        } else {
            rowView.background = null
            textTitle.setTextColor(Color.WHITE)
            textDetails.setTextColor(Color.parseColor("#A0A0B0"))
            btnOptions?.setColorFilter(Color.parseColor("#A0A0B0"))
        }

        return rowView
    }
}
