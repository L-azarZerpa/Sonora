package com.example.sonora

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
        private const val PREFS_NAME = "SonoraPrefs"
        private const val KEY_LAST_SONG_ID = "LAST_SONG_ID"
        private const val KEY_LAST_SONG_POS = "LAST_SONG_POS"
        private const val KEY_PLAYLISTS_EXPANDED = "KEY_PLAYLISTS_EXPANDED"
        private const val KEY_PLAYLISTS_DATA = "KEY_PLAYLISTS_DATA"
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var tabNavList: View
    private lateinit var tabNavPlayer: View
    private lateinit var tabNavPlaylists: View
    private lateinit var indicatorNavList: View
    private lateinit var indicatorNavPlayer: View
    private lateinit var indicatorNavPlaylists: View
    private lateinit var btnNavList: ImageButton
    private lateinit var btnNavPlayer: ImageButton
    private lateinit var btnNavPlaylists: ImageButton

    val playlistsList = mutableListOf<Playlist>()

    // Reproductor de audio y control de estado
    var mediaPlayer: MediaPlayer? = null
    var currentPlayingSong: Song? = null
    var currentPlayingPlaylist: Playlist? = null
    var activeOpenedPlaylist: Playlist? = null
    var currentPlayingIndex: Int = -1
    var isPlaying: Boolean = false
    var isUserSeeking: Boolean = false

    // Modos de reproducción
    var isRepeatOne: Boolean = false
    var isShuffleEnabled: Boolean = false

    private val progressHandler = Handler(Looper.getMainLooper())

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && isPlaying && !isUserSeeking) {
                val currentPos = player.currentPosition
                pagerAdapter.activePlayerViewHolder?.let { vh ->
                    vh.playerSeekBar.progress = currentPos
                    vh.tvCurrentTime.text = formatDuration(currentPos.toLong())
                }
            }
            if (isPlaying) {
                progressHandler.postDelayed(this, 500)
            }
        }
    }

    // Solicitud de permisos múltiples
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val audioPermission = getAudioPermission()
            val audioGranted = permissions[audioPermission] ?: false

            if (audioGranted) {
                onPermissionGranted()
            } else {
                onPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_main)

        MediaNotificationHelper.createNotificationChannel(this)

        viewPager = findViewById(R.id.viewPager)
        pagerAdapter = MainPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = 2

        tabNavList = findViewById(R.id.tabNavList)
        tabNavPlayer = findViewById(R.id.tabNavPlayer)
        tabNavPlaylists = findViewById(R.id.tabNavPlaylists)

        indicatorNavList = findViewById(R.id.indicatorNavList)
        indicatorNavPlayer = findViewById(R.id.indicatorNavPlayer)
        indicatorNavPlaylists = findViewById(R.id.indicatorNavPlaylists)

        btnNavList = findViewById(R.id.btnNavList)
        btnNavPlayer = findViewById(R.id.btnNavPlayer)
        btnNavPlaylists = findViewById(R.id.btnNavPlaylists)

        tabNavList.setOnClickListener {
            setCurrentTab(0)
        }

        tabNavPlayer.setOnClickListener {
            setCurrentTab(1)
        }

        tabNavPlaylists.setOnClickListener {
            setCurrentTab(2)
        }

        loadPlaylistsFromPrefs()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateBottomNavColors(position)
            }
        })

        updateBottomNavColors(viewPager.currentItem)

        // Control del botón Atrás del sistema
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val playlistsVh = pagerAdapter.activePlaylistsViewHolder
                if (viewPager.currentItem == 2 && playlistsVh?.viewPlaylistDetail?.visibility == View.VISIBLE) {
                    playlistsVh.viewPlaylistDetail.visibility = View.GONE
                    playlistsVh.viewPlaylistsMain.visibility = View.VISIBLE
                    activeOpenedPlaylist = null
                    pagerAdapter.notifyItemChanged(2)
                } else if (viewPager.currentItem != 0) {
                    setCurrentTab(0)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        checkAndRequestAppPermissions()
    }

    fun setCurrentTab(position: Int) {
        viewPager.setCurrentItem(position, true)
    }

    fun setViewPagerUserInputEnabled(enabled: Boolean) {
        viewPager.isUserInputEnabled = enabled
    }

    fun postDelayedToProgressHandler(runnable: Runnable, delayMs: Long) {
        progressHandler.postDelayed(runnable, delayMs)
    }

    fun removeCallbacksFromProgressHandler(runnable: Runnable) {
        progressHandler.removeCallbacks(runnable)
    }

    private fun updateBottomNavColors(position: Int) {
        val white = Color.WHITE
        val inactiveColor = Color.parseColor("#555566")

        btnNavList.setColorFilter(if (position == 0) white else inactiveColor)
        indicatorNavList.visibility = if (position == 0) View.VISIBLE else View.INVISIBLE

        btnNavPlayer.setColorFilter(if (position == 1) white else inactiveColor)
        indicatorNavPlayer.visibility = if (position == 1) View.VISIBLE else View.INVISIBLE

        btnNavPlaylists.setColorFilter(if (position == 2) white else inactiveColor)
        indicatorNavPlaylists.visibility = if (position == 2) View.VISIBLE else View.INVISIBLE
    }

    fun savePlaylistsToPrefs() {
        val array = JSONArray()
        for (pl in playlistsList) {
            val obj = JSONObject()
            obj.put("name", pl.name)
            val idsArray = JSONArray()
            for (id in pl.songIds) {
                idsArray.put(id)
            }
            obj.put("songIds", idsArray)
            array.put(obj)
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYLISTS_DATA, array.toString())
            .apply()
    }

    private fun loadPlaylistsFromPrefs() {
        playlistsList.clear()
        val jsonStr = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYLISTS_DATA, null) ?: return
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val idsArray = obj.getJSONArray("songIds")
                val ids = mutableListOf<Long>()
                for (j in 0 until idsArray.length()) {
                    ids.add(idsArray.getLong(j))
                }
                playlistsList.add(Playlist(name, ids))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showCreatePlaylistDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val input = EditText(this).apply {
            hint = "Nombre de la lista"
            setPadding(32, 32, 32, 32)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#808080"))
            setBackgroundColor(Color.parseColor("#22222C"))
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Nueva Lista de Reproducción")
            .setMessage("Escribe el nombre de la nueva lista:")
            .setView(container)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (playlistsList.any { it.name.equals(name, ignoreCase = true) }) {
                        Toast.makeText(this, "Ya existe una lista con ese nombre", Toast.LENGTH_SHORT).show()
                    } else {
                        val newPlaylist = Playlist(name)
                        playlistsList.add(newPlaylist)
                        savePlaylistsToPrefs()
                        pagerAdapter.notifyItemChanged(2)
                        Toast.makeText(this, "Lista '$name' creada", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showSongOptionsMenuDialog(song: Song) {
        val options = arrayOf("Agregar a lista de reproducción")
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        dialog.dismiss()
                        showAddSongToPlaylistDialog(song)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showAddSongToPlaylistDialog(song: Song) {
        if (playlistsList.isEmpty()) {
            Toast.makeText(this, "Crea primero una lista de reproducción en la pestaña 'Mis Listas'", Toast.LENGTH_LONG).show()
            return
        }

        val playlistNames = playlistsList.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Agregar '${song.title}' a:")
            .setItems(playlistNames) { _, which ->
                val selectedPlaylist = playlistsList[which]
                if (selectedPlaylist.songIds.contains(song.id)) {
                    Toast.makeText(this, "La canción ya está en '${selectedPlaylist.name}'", Toast.LENGTH_SHORT).show()
                } else {
                    selectedPlaylist.songIds.add(song.id)
                    savePlaylistsToPrefs()
                    pagerAdapter.notifyItemChanged(2)
                    Toast.makeText(this, "Añadida a '${selectedPlaylist.name}'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkAndRequestAppPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        val audioPermission = getAudioPermission()

        if (ContextCompat.checkSelfPermission(this, audioPermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(audioPermission)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            onPermissionGranted()
        } else {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun getAudioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun onPermissionGranted() {
        Toast.makeText(this, "Permisos concedidos", Toast.LENGTH_SHORT).show()
        scanMusicFiles()
    }

    private fun onPermissionDenied() {
        pagerAdapter.updateScanning(false)
        Toast.makeText(this, "Permiso denegado para acceder al audio", Toast.LENGTH_LONG).show()
    }

    /**
     * Escanea la memoria del dispositivo en segundo plano usando MediaStore.
     */
    private fun scanMusicFiles() {
        pagerAdapter.updateScanning(true)

        Executors.newSingleThreadExecutor().execute {
            val songs = MusicScanner.loadAudioFiles(this)
            runOnUiThread {
                pagerAdapter.updateSongs(songs)
                if (songs.isNotEmpty()) {
                    restoreLastPlayedSong(songs)
                }
            }
        }
    }

    /**
     * Recuerda y restaura la última canción escuchada desde SharedPreferences.
     */
    private fun restoreLastPlayedSong(songs: List<Song>) {
        if (currentPlayingSong != null) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSongId = prefs.getLong(KEY_LAST_SONG_ID, -1L)
        val lastPos = prefs.getInt(KEY_LAST_SONG_POS, 0)

        if (lastSongId != -1L) {
            val lastSong = songs.find { it.id == lastSongId }
            if (lastSong != null) {
                currentPlayingSong = lastSong
                currentPlayingIndex = songs.indexOf(lastSong)
                pagerAdapter.notifyItemChanged(1)

                pagerAdapter.activePlayerViewHolder?.let { vh ->
                    vh.playerSeekBar.max = lastSong.duration.toInt()
                    vh.playerSeekBar.progress = lastPos
                    vh.tvCurrentTime.text = formatDuration(lastPos.toLong())
                    vh.tvTotalTime.text = formatDuration(lastSong.duration)
                }
            }
        }
    }

    fun saveLastPlayedSong(song: Song, positionMs: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_SONG_ID, song.id)
            .putInt(KEY_LAST_SONG_POS, positionMs)
            .apply()
    }

    /**
     * Reproduce una canción seleccionada utilizando MediaPlayer.
     */
    fun playSong(song: Song, startPositionMs: Int = 0, playlistContext: Playlist? = null) {
        currentPlayingPlaylist = playlistContext
        val allSongs = pagerAdapter.getSongsList()

        val activeSongs = if (currentPlayingPlaylist != null) {
            val filtered = allSongs.filter { currentPlayingPlaylist!!.songIds.contains(it.id) }
            if (filtered.isNotEmpty()) filtered else allSongs
        } else {
            allSongs
        }

        currentPlayingIndex = activeSongs.indexOfFirst { it.id == song.id }

        if (currentPlayingSong?.id == song.id && mediaPlayer != null) {
            if (!mediaPlayer!!.isPlaying) {
                mediaPlayer!!.start()
                this@MainActivity.isPlaying = true
                startProgressUpdates()
                showMediaNotification()
                pagerAdapter.notifyItemChanged(1)
            }
            pagerAdapter.notifyItemChanged(0)
            pagerAdapter.notifyItemChanged(2)
            return
        }

        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
            mediaPlayer = null

            currentPlayingSong = song
            saveLastPlayedSong(song, startPositionMs)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                setDataSource(applicationContext, song.uri)
                prepare()
                if (startPositionMs > 0) {
                    seekTo(startPositionMs)
                }
                start()

                setOnCompletionListener {
                    if (isRepeatOne && currentPlayingSong != null) {
                        val songToRepeat = currentPlayingSong!!
                        mediaPlayer?.stop()
                        mediaPlayer?.reset()
                        mediaPlayer?.release()
                        mediaPlayer = null
                        playSong(songToRepeat, 0, currentPlayingPlaylist)
                    } else {
                        playNextSong()
                    }
                }
            }

            this@MainActivity.isPlaying = true
            startProgressUpdates()
            showMediaNotification()
            pagerAdapter.notifyItemChanged(0)
            pagerAdapter.notifyItemChanged(1)
            pagerAdapter.notifyItemChanged(2)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "No se pudo reproducir el archivo", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Configura la velocidad de reproducción del MediaPlayer (ej. 2.0x para acelerar).
     */
    fun setPlaybackSpeed(speed: Float) {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) {
                val params = player.playbackParams
                params.speed = speed
                player.playbackParams = params
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Alterna entre reproducir y pausar la canción actual.
     */
    fun togglePlayPause() {
        val player = mediaPlayer
        if (player != null) {
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
                stopProgressUpdates()
                currentPlayingSong?.let { saveLastPlayedSong(it, player.currentPosition) }
            } else {
                player.start()
                isPlaying = true
                startProgressUpdates()
            }
            showMediaNotification()
            pagerAdapter.notifyItemChanged(1)
        } else if (currentPlayingSong != null) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedPos = prefs.getInt(KEY_LAST_SONG_POS, 0)
            playSong(currentPlayingSong!!, savedPos, currentPlayingPlaylist)
        } else {
            Toast.makeText(this, "Selecciona una canción primero", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Pasa a la siguiente canción en la lista (respeta el modo aleatorio/shuffle si está activo).
     */
    fun playNextSong() {
        val allSongs = pagerAdapter.getSongsList()
        val songs = if (currentPlayingPlaylist != null) {
            val filtered = allSongs.filter { currentPlayingPlaylist!!.songIds.contains(it.id) }
            if (filtered.isNotEmpty()) filtered else allSongs
        } else {
            allSongs
        }

        if (songs.isEmpty()) return
        val nextIndex = if (isShuffleEnabled && songs.size > 1) {
            var randomPos = songs.indices.random()
            while (randomPos == currentPlayingIndex) {
                randomPos = songs.indices.random()
            }
            randomPos
        } else {
            if (currentPlayingIndex < songs.size - 1) currentPlayingIndex + 1 else 0
        }
        playSong(songs[nextIndex], 0, currentPlayingPlaylist)
    }

    /**
     * Regresa a la canción anterior en la lista (respeta el modo aleatorio/shuffle si está activo).
     */
    fun playPreviousSong() {
        val allSongs = pagerAdapter.getSongsList()
        val songs = if (currentPlayingPlaylist != null) {
            val filtered = allSongs.filter { currentPlayingPlaylist!!.songIds.contains(it.id) }
            if (filtered.isNotEmpty()) filtered else allSongs
        } else {
            allSongs
        }

        if (songs.isEmpty()) return
        val prevIndex = if (isShuffleEnabled && songs.size > 1) {
            var randomPos = songs.indices.random()
            while (randomPos == currentPlayingIndex) {
                randomPos = songs.indices.random()
            }
            randomPos
        } else {
            if (currentPlayingIndex > 0) currentPlayingIndex - 1 else songs.size - 1
        }
        playSong(songs[prevIndex], 0, currentPlayingPlaylist)
    }

    /**
     * Adelanta 5 segundos la posición actual de reproducción.
     */
    fun forward5Seconds() {
        val player = mediaPlayer ?: return
        val duration = currentPlayingSong?.duration?.toInt() ?: player.duration
        val newPos = minOf(duration, player.currentPosition + 5000)
        player.seekTo(newPos)
        pagerAdapter.activePlayerViewHolder?.let { vh ->
            vh.playerSeekBar.progress = newPos
            vh.tvCurrentTime.text = formatDuration(newPos.toLong())
        }
        currentPlayingSong?.let { saveLastPlayedSong(it, newPos) }
        showMediaNotification()
    }

    /**
     * Retrocede 5 segundos la posición actual de reproducción.
     */
    fun rewind5Seconds() {
        val player = mediaPlayer ?: return
        val newPos = maxOf(0, player.currentPosition - 5000)
        player.seekTo(newPos)
        pagerAdapter.activePlayerViewHolder?.let { vh ->
            vh.playerSeekBar.progress = newPos
            vh.tvCurrentTime.text = formatDuration(newPos.toLong())
        }
        currentPlayingSong?.let { saveLastPlayedSong(it, newPos) }
        showMediaNotification()
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(updateProgressRunnable)
        progressHandler.post(updateProgressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(updateProgressRunnable)
    }

    fun showMediaNotification() {
        val currentPos = mediaPlayer?.currentPosition ?: 0
        MediaNotificationHelper.showMediaNotification(this, currentPlayingSong, isPlaying, currentPos)
    }

    private fun cancelMediaNotification() {
        MediaNotificationHelper.cancelMediaNotification(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        val currentPos = mediaPlayer?.currentPosition ?: 0
        currentPlayingSong?.let { saveLastPlayedSong(it, currentPos) }
        stopProgressUpdates()
        cancelMediaNotification()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
