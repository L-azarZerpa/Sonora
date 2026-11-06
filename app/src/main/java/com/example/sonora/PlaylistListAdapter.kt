package com.example.sonora

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

class PlaylistListAdapter(
    context: Context,
    playlists: List<Playlist>,
    private val getCurrentPlayingPlaylistName: () -> String?,
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onPlaylistDeleteClick: (Playlist) -> Unit
) : ArrayAdapter<Playlist>(context, R.layout.item_playlist, R.id.tvPlaylistName, playlists) {

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val rowView = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_playlist, parent, false)
        val playlist = getItem(pos)

        val ivIcon = rowView.findViewById<ImageView>(R.id.ivPlaylistIcon)
        val tvName = rowView.findViewById<TextView>(R.id.tvPlaylistName)
        val tvCount = rowView.findViewById<TextView>(R.id.tvPlaylistCount)
        val btnDelete = rowView.findViewById<ImageButton>(R.id.btnDeletePlaylist)

        tvName.text = playlist?.name
        val count = playlist?.songIds?.size ?: 0
        tvCount.text = if (count == 1) "1 canción" else "$count canciones"

        val pinkColor = Color.parseColor("#F7CDDA")
        val currentPlayingPlaylistName = getCurrentPlayingPlaylistName()
        val isPlaylistPlaying = playlist != null && currentPlayingPlaylistName != null && playlist.name == currentPlayingPlaylistName

        if (isPlaylistPlaying) {
            tvName.setTextColor(pinkColor)
            ivIcon?.setColorFilter(pinkColor)
        } else {
            tvName.setTextColor(Color.WHITE)
            ivIcon?.setColorFilter(Color.WHITE)
        }

        rowView.setOnClickListener {
            playlist?.let { pl -> onPlaylistClick(pl) }
        }

        btnDelete.setOnClickListener {
            playlist?.let { pl -> onPlaylistDeleteClick(pl) }
        }

        return rowView
    }
}
