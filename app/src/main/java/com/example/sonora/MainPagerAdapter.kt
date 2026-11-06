package com.example.sonora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainPagerAdapter(
    private val activity: MainActivity
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var songs: List<Song> = emptyList()
    private var isScanning: Boolean = false

    var activePlayerViewHolder: PlayerViewHolder? = null
        private set
    var activePlaylistsViewHolder: PlaylistsViewHolder? = null
        private set

    fun getSongsList(): List<Song> = songs

    fun updateScanning(scanning: Boolean) {
        isScanning = scanning
        notifyItemChanged(0)
    }

    fun updateSongs(newSongs: List<Song>) {
        songs = newSongs
        isScanning = false
        notifyItemChanged(0)
        notifyItemChanged(2)
    }

    override fun getItemCount(): Int = 3

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> {
                val view = inflater.inflate(R.layout.page_songs, parent, false)
                SongsViewHolder(view)
            }
            1 -> {
                val view = inflater.inflate(R.layout.page_player, parent, false)
                PlayerViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.page_playlists, parent, false)
                PlaylistsViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SongsViewHolder) {
            holder.progressBar.visibility = if (isScanning) View.VISIBLE else View.GONE

            val listAdapter = SongListAdapter(
                context = activity,
                songs = songs,
                getCurrentPlayingSongId = { activity.currentPlayingSong?.id },
                onSongClick = { clickedSong -> activity.playSong(clickedSong, 0, null) },
                onSongOptionsClick = { song -> activity.showSongOptionsMenuDialog(song) }
            )

            holder.lvSongs.adapter = listAdapter
            holder.lvSongs.setOnItemClickListener { _, _, songPos, _ ->
                val clickedSong = songs[songPos]
                activity.playSong(clickedSong, 0, null)
            }
        } else if (holder is PlayerViewHolder) {
            activePlayerViewHolder = holder
            val song = activity.currentPlayingSong
            val player = activity.mediaPlayer

            if (song != null) {
                holder.tvPlayerTitle.text = song.title
                holder.tvPlayerArtist.text = song.artist
                holder.tvTotalTime.text = formatDuration(song.duration)
                holder.playerSeekBar.max = song.duration.toInt()

                val currentPos = player?.currentPosition ?: 0
                holder.playerSeekBar.progress = currentPos
                holder.tvCurrentTime.text = formatDuration(currentPos.toLong())
            } else {
                holder.tvPlayerTitle.text = "Sin canción seleccionada"
                holder.tvPlayerArtist.text = "Selecciona una canción en la lista"
                holder.tvCurrentTime.text = "00:00"
                holder.tvTotalTime.text = "00:00"
                holder.playerSeekBar.max = 100
                holder.playerSeekBar.progress = 0
            }

            holder.btnPlayPause.setImageResource(
                if (activity.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            )

            updateRepeatButtonUI(holder.btnRepeat)
            updateShuffleButtonUI(holder.btnShuffle)

            holder.btnRepeat.setOnClickListener {
                activity.isRepeatOne = !activity.isRepeatOne
                updateRepeatButtonUI(holder.btnRepeat)
                val msg = if (activity.isRepeatOne) "Modo: En bucle la misma canción" else "Modo: Siguiente canción automáticamente"
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }

            holder.btnShuffle.setOnClickListener {
                activity.isShuffleEnabled = !activity.isShuffleEnabled
                updateShuffleButtonUI(holder.btnShuffle)
                val msg = if (activity.isShuffleEnabled) "Modo: Reproducción al azar" else "Modo: Reproducción lineal"
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }

            holder.btnPlayPause.setOnClickListener {
                activity.togglePlayPause()
            }

            holder.btnPrevious.setOnClickListener {
                activity.playPreviousSong()
            }

            holder.btnNext.setOnClickListener {
                activity.playNextSong()
            }

            setupForwardTouchListener(holder.btnForward5)
            setupRewindTouchListener(holder.btnRewind5)

            holder.btnBackToList.setOnClickListener {
                activity.setCurrentTab(0)
            }

            // Control del SeekBar para arrastrar y adelantar/retroceder la canción
            holder.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        holder.tvCurrentTime.text = formatDuration(progress.toLong())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    activity.isUserSeeking = true
                    activity.setViewPagerUserInputEnabled(false)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val targetPosition = seekBar?.progress ?: 0
                    activity.mediaPlayer?.seekTo(targetPosition)
                    holder.tvCurrentTime.text = formatDuration(targetPosition.toLong())
                    activity.isUserSeeking = false
                    activity.setViewPagerUserInputEnabled(true)
                    activity.currentPlayingSong?.let { activity.saveLastPlayedSong(it, targetPosition) }
                }
            })
        } else if (holder is PlaylistsViewHolder) {
            activePlaylistsViewHolder = holder
            val prefs = activity.getSharedPreferences("SonoraPrefs", Context.MODE_PRIVATE)
            var isExpanded = prefs.getBoolean("KEY_PLAYLISTS_EXPANDED", true)

            fun updateExpandState(expanded: Boolean) {
                holder.containerPlaylists.visibility = if (expanded) View.VISIBLE else View.GONE
                holder.ivExpandArrow.rotation = if (expanded) 0f else -90f
            }

            updateExpandState(isExpanded)

            holder.btnTogglePlaylists.setOnClickListener {
                isExpanded = !isExpanded
                updateExpandState(isExpanded)
                prefs.edit().putBoolean("KEY_PLAYLISTS_EXPANDED", isExpanded).apply()
            }

            holder.btnNewPlaylist.setOnClickListener {
                activity.showCreatePlaylistDialog()
            }

            holder.btnBackToPlaylistsMain.setOnClickListener {
                activity.activeOpenedPlaylist = null
                holder.viewPlaylistDetail.visibility = View.GONE
                holder.viewPlaylistsMain.visibility = View.VISIBLE
                notifyItemChanged(2)
            }

            // Restaurar vista detalle de lista si ya estaba abierta
            val activeOpenedPlaylist = activity.activeOpenedPlaylist
            if (activeOpenedPlaylist != null) {
                val openedPl = activity.playlistsList.find { it.name == activeOpenedPlaylist.name }
                if (openedPl != null) {
                    holder.viewPlaylistsMain.visibility = View.GONE
                    holder.viewPlaylistDetail.visibility = View.VISIBLE
                    holder.tvSelectedPlaylistTitle.text = openedPl.name
                    updatePlaylistSongsDetailList(holder, openedPl)
                } else {
                    activity.activeOpenedPlaylist = null
                    holder.viewPlaylistDetail.visibility = View.GONE
                    holder.viewPlaylistsMain.visibility = View.VISIBLE
                }
            } else {
                holder.viewPlaylistDetail.visibility = View.GONE
                holder.viewPlaylistsMain.visibility = View.VISIBLE
            }

            if (activity.playlistsList.isEmpty()) {
                holder.tvEmptyPlaylists.visibility = View.VISIBLE
                holder.lvPlaylists.visibility = View.GONE
            } else {
                holder.tvEmptyPlaylists.visibility = View.GONE
                holder.lvPlaylists.visibility = View.VISIBLE

                val playlistAdapter = PlaylistListAdapter(
                    context = activity,
                    playlists = activity.playlistsList,
                    getCurrentPlayingPlaylistName = { activity.currentPlayingPlaylist?.name },
                    onPlaylistClick = { clickedPlaylist ->
                        val isAlreadyPlayingThisPlaylist = (activity.currentPlayingPlaylist?.name == clickedPlaylist.name)
                        activity.activeOpenedPlaylist = clickedPlaylist

                        if (!isAlreadyPlayingThisPlaylist) {
                            activity.currentPlayingPlaylist = clickedPlaylist
                            val allSongs = getSongsList()
                            val playlistSongs = allSongs.filter { clickedPlaylist.songIds.contains(it.id) }
                            if (playlistSongs.isNotEmpty()) {
                                activity.playSong(playlistSongs[0], 0, clickedPlaylist)
                            }
                        }

                        holder.viewPlaylistsMain.visibility = View.GONE
                        holder.viewPlaylistDetail.visibility = View.VISIBLE
                        holder.tvSelectedPlaylistTitle.text = clickedPlaylist.name

                        updatePlaylistSongsDetailList(holder, clickedPlaylist)
                        notifyItemChanged(2)
                    },
                    onPlaylistDeleteClick = { pl ->
                        if (activity.currentPlayingPlaylist == pl) {
                            activity.currentPlayingPlaylist = null
                        }
                        if (activity.activeOpenedPlaylist == pl) {
                            activity.activeOpenedPlaylist = null
                            holder.viewPlaylistDetail.visibility = View.GONE
                            holder.viewPlaylistsMain.visibility = View.VISIBLE
                        }
                        activity.playlistsList.remove(pl)
                        activity.savePlaylistsToPrefs()
                        notifyItemChanged(2)
                        if (activity.playlistsList.isEmpty()) {
                            holder.tvEmptyPlaylists.visibility = View.VISIBLE
                            holder.lvPlaylists.visibility = View.GONE
                        }
                        Toast.makeText(activity, "Lista '${pl.name}' eliminada", Toast.LENGTH_SHORT).show()
                    }
                )

                holder.lvPlaylists.adapter = playlistAdapter
            }
        }
    }

    fun updatePlaylistSongsDetailList(holder: PlaylistsViewHolder, playlist: Playlist) {
        val allSongs = getSongsList()
        val playlistSongs = allSongs.filter { playlist.songIds.contains(it.id) }

        if (playlistSongs.isEmpty()) {
            holder.tvEmptyPlaylistDetail.visibility = View.VISIBLE
            holder.lvPlaylistSongsDetail.visibility = View.GONE
        } else {
            holder.tvEmptyPlaylistDetail.visibility = View.GONE
            holder.lvPlaylistSongsDetail.visibility = View.VISIBLE

            val detailAdapter = SongListAdapter(
                context = activity,
                songs = playlistSongs,
                getCurrentPlayingSongId = { activity.currentPlayingSong?.id },
                onSongClick = { clickedSong ->
                    activity.playSong(clickedSong, 0, playlist)
                    notifyItemChanged(2)
                },
                onSongOptionsClick = { song -> activity.showSongOptionsMenuDialog(song) }
            )

            holder.lvPlaylistSongsDetail.adapter = detailAdapter
        }
    }

    private fun updateRepeatButtonUI(btn: ImageButton) {
        val activeColor = Color.parseColor("#F7CDDA")
        val inactiveColor = Color.parseColor("#555566")

        if (activity.isRepeatOne) {
            btn.setImageResource(R.drawable.ic_repeat_one)
            btn.setColorFilter(activeColor)
        } else {
            btn.setImageResource(R.drawable.ic_repeat)
            btn.setColorFilter(inactiveColor)
        }
    }

    private fun updateShuffleButtonUI(btn: ImageButton) {
        val activeColor = Color.parseColor("#F7CDDA")
        val inactiveColor = Color.parseColor("#555566")

        btn.setImageResource(R.drawable.ic_shuffle)
        if (activity.isShuffleEnabled) {
            btn.setColorFilter(activeColor)
        } else {
            btn.setColorFilter(inactiveColor)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupForwardTouchListener(btn: ImageButton) {
        btn.setOnTouchListener(object : View.OnTouchListener {
            private var isLongPress = false
            private var toast2x: Toast? = null
            private val longPressRunnable = Runnable {
                isLongPress = true
                activity.setPlaybackSpeed(2.0f)
                toast2x = Toast.makeText(activity, "Velocidad 2x", Toast.LENGTH_SHORT)
                toast2x?.show()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isLongPress = false
                        activity.postDelayedToProgressHandler(longPressRunnable, 350)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activity.removeCallbacksFromProgressHandler(longPressRunnable)
                        if (isLongPress) {
                            activity.setPlaybackSpeed(1.0f)
                            toast2x?.cancel()
                        } else {
                            activity.forward5Seconds()
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRewindTouchListener(btn: ImageButton) {
        btn.setOnTouchListener(object : View.OnTouchListener {
            private var isLongPress = false
            private var toast2x: Toast? = null
            private val longPressRunnable = Runnable {
                isLongPress = true
                activity.setPlaybackSpeed(2.0f)
                toast2x = Toast.makeText(activity, "Velocidad 2x", Toast.LENGTH_SHORT)
                toast2x?.show()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isLongPress = false
                        activity.postDelayedToProgressHandler(longPressRunnable, 350)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activity.removeCallbacksFromProgressHandler(longPressRunnable)
                        if (isLongPress) {
                            activity.setPlaybackSpeed(1.0f)
                            toast2x?.cancel()
                        } else {
                            activity.rewind5Seconds()
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    class SongsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val lvSongs: ListView = view.findViewById(R.id.lvSongs)
    }

    class PlayerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btnBackToList: ImageButton = view.findViewById(R.id.btnBackToList)
        val tvPlayerTitle: TextView = view.findViewById(R.id.tvPlayerTitle)
        val tvPlayerArtist: TextView = view.findViewById(R.id.tvPlayerArtist)
        val btnRepeat: ImageButton = view.findViewById(R.id.btnRepeat)
        val btnShuffle: ImageButton = view.findViewById(R.id.btnShuffle)
        val tvCurrentTime: TextView = view.findViewById(R.id.tvCurrentTime)
        val tvTotalTime: TextView = view.findViewById(R.id.tvTotalTime)
        val playerSeekBar: SeekBar = view.findViewById(R.id.playerSeekBar)
        val btnPrevious: ImageButton = view.findViewById(R.id.btnPrevious)
        val btnRewind5: ImageButton = view.findViewById(R.id.btnRewind5)
        val btnPlayPause: FloatingActionButton = view.findViewById(R.id.btnPlayPause)
        val btnForward5: ImageButton = view.findViewById(R.id.btnForward5)
        val btnNext: ImageButton = view.findViewById(R.id.btnNext)
    }

    class PlaylistsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewPlaylistsMain: View = view.findViewById(R.id.viewPlaylistsMain)
        val btnNewPlaylist: View = view.findViewById(R.id.btnNewPlaylist)
        val btnTogglePlaylists: View = view.findViewById(R.id.btnTogglePlaylists)
        val ivExpandArrow: ImageView = view.findViewById(R.id.ivExpandArrow)
        val containerPlaylists: View = view.findViewById(R.id.containerPlaylists)
        val tvEmptyPlaylists: TextView = view.findViewById(R.id.tvEmptyPlaylists)
        val lvPlaylists: ListView = view.findViewById(R.id.lvPlaylists)

        val viewPlaylistDetail: View = view.findViewById(R.id.viewPlaylistDetail)
        val btnBackToPlaylistsMain: ImageButton = view.findViewById(R.id.btnBackToPlaylistsMain)
        val tvSelectedPlaylistTitle: TextView = view.findViewById(R.id.tvSelectedPlaylistTitle)
        val tvEmptyPlaylistDetail: TextView = view.findViewById(R.id.tvEmptyPlaylistDetail)
        val lvPlaylistSongsDetail: ListView = view.findViewById(R.id.lvPlaylistSongsDetail)
    }
}
