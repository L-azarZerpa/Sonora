package com.example.sonora

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
        private const val PREFS_NAME = "SonoraPrefs"
        private const val KEY_LAST_SONG_ID = "LAST_SONG_ID"
        private const val KEY_LAST_SONG_POS = "LAST_SONG_POS"
        private const val KEY_PLAYLISTS_EXPANDED = "KEY_PLAYLISTS_EXPANDED"
        private const val KEY_PLAYLISTS_DATA = "KEY_PLAYLISTS_DATA"
        private const val CHANNEL_ID = "sonora_media_channel"
        private const val NOTIFICATION_ID = 2001
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

    private val playlistsList = mutableListOf<Playlist>()

    // Reproductor de audio y control de estado
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingSong: Song? = null
    private var currentPlayingPlaylist: Playlist? = null
    private var activeOpenedPlaylist: Playlist? = null
    private var currentPlayingIndex: Int = -1
    private var isPlaying: Boolean = false
    private var isUserSeeking: Boolean = false

    // Modos de reproducción
    private var isRepeatOne: Boolean = false
    private var isShuffleEnabled: Boolean = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private var activePlayerViewHolder: MainPagerAdapter.PlayerViewHolder? = null
    private var activePlaylistsViewHolder: MainPagerAdapter.PlaylistsViewHolder? = null
    private var lastNotificationUpdateMs = 0L

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && isPlaying && !isUserSeeking) {
                val currentPos = player.currentPosition
                activePlayerViewHolder?.let { vh ->
                    vh.playerSeekBar.progress = currentPos
                    vh.tvCurrentTime.text = formatDuration(currentPos.toLong())
                }
                val now = System.currentTimeMillis()
                if (now - lastNotificationUpdateMs >= 1000) {
                    lastNotificationUpdateMs = now
                    showMediaNotification()
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

        createNotificationChannel()

        viewPager = findViewById(R.id.viewPager)
        pagerAdapter = MainPagerAdapter()
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
            viewPager.setCurrentItem(0, true)
        }

        tabNavPlayer.setOnClickListener {
            viewPager.setCurrentItem(1, true)
        }

        tabNavPlaylists.setOnClickListener {
            viewPager.setCurrentItem(2, true)
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
                val playlistsVh = activePlaylistsViewHolder
                if (viewPager.currentItem == 2 && playlistsVh?.viewPlaylistDetail?.visibility == View.VISIBLE) {
                    playlistsVh.viewPlaylistDetail.visibility = View.GONE
                    playlistsVh.viewPlaylistsMain.visibility = View.VISIBLE
                    activeOpenedPlaylist = null
                    pagerAdapter.notifyItemChanged(2)
                } else if (viewPager.currentItem != 0) {
                    viewPager.setCurrentItem(0, true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        checkAndRequestAppPermissions()
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

    private fun savePlaylistsToPrefs() {
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
            val songs = loadAudioFiles()
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

                activePlayerViewHolder?.let { vh ->
                    vh.playerSeekBar.max = lastSong.duration.toInt()
                    vh.playerSeekBar.progress = lastPos
                    vh.tvCurrentTime.text = formatDuration(lastPos.toLong())
                    vh.tvTotalTime.text = formatDuration(lastSong.duration)
                }
            }
        }
    }

    private fun saveLastPlayedSong(song: Song, positionMs: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_SONG_ID, song.id)
            .putInt(KEY_LAST_SONG_POS, positionMs)
            .apply()
    }

    /**
     * Consulta el proveedor de contenidos MediaStore para obtener la lista de pistas de audio.
     */
    private fun loadAudioFiles(): List<Song> {
        val songsList = mutableListOf<Song>()

        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn) ?: "Título desconocido"
                    val artist = cursor.getString(artistColumn) ?: "Artista desconocido"
                    val duration = cursor.getLong(durationColumn)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )

                    songsList.add(Song(id, title, artist, duration, contentUri))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return songsList
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
        activePlayerViewHolder?.let { vh ->
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
        activePlayerViewHolder?.let { vh ->
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

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    // --- Notificaciones multimedia y Controles fuera de la app ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproductor Sonora",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Controles de reproducción para Sonora"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun showMediaNotification() {
        val song = currentPlayingSong ?: return
        val player = mediaPlayer

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(this, MusicControlReceiver::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                this,
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

        val currentPos = player?.currentPosition ?: 0
        val duration = song.duration.toInt()

        val pinkColor = Color.parseColor("#F7CDDA")
        val whiteColor = Color.WHITE
        val bgDarkColor = Color.parseColor("#0F0F12")

        val remoteViews = RemoteViews(packageName, R.layout.notification_media).apply {
            setInt(R.id.rootNotificationMedia, "setBackgroundColor", bgDarkColor)

            setTextViewText(R.id.tvNotificationTitle, song.title)
            setTextViewText(R.id.tvNotificationArtist, song.artist)
            setTextViewText(R.id.tvNotificationCurrentTime, formatDuration(currentPos.toLong()))
            setTextViewText(R.id.tvNotificationTotalTime, formatDuration(song.duration))

            setProgressBar(R.id.pbNotificationProgress, duration, currentPos, false)

            setImageViewResource(
                R.id.btnNotificationPlayPause,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            )
            setInt(R.id.btnNotificationPlayPause, "setColorFilter", bgDarkColor)

            setInt(R.id.btnNotificationPrev, "setColorFilter", whiteColor)
            setInt(R.id.btnNotificationRewind5, "setColorFilter", pinkColor)
            setInt(R.id.btnNotificationForward5, "setColorFilter", pinkColor)
            setInt(R.id.btnNotificationNext, "setColorFilter", whiteColor)

            setOnClickPendingIntent(R.id.btnNotificationPrev, prevIntent)
            setOnClickPendingIntent(R.id.btnNotificationRewind5, rewindIntent)
            setOnClickPendingIntent(R.id.btnNotificationPlayPause, playPauseIntent)
            setOnClickPendingIntent(R.id.btnNotificationForward5, forwardIntent)
            setOnClickPendingIntent(R.id.btnNotificationNext, nextIntent)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelMediaNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(NOTIFICATION_ID)
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

    inner class MainPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var songs: List<Song> = emptyList()
        private var isScanning: Boolean = false

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

                val listAdapter = object : ArrayAdapter<Song>(this@MainActivity, R.layout.item_song, R.id.tvSongTitle, songs) {
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
                            song?.let { s -> showSongOptionsMenuDialog(s) }
                        }

                        rowView.setOnClickListener {
                            song?.let { s -> playSong(s, 0, null) }
                        }

                        val isSelected = song != null && currentPlayingSong != null && song.id == currentPlayingSong?.id
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

                holder.lvSongs.adapter = listAdapter
                holder.lvSongs.setOnItemClickListener { _, _, songPos, _ ->
                    val clickedSong = songs[songPos]
                    playSong(clickedSong, 0, null)
                }
            } else if (holder is PlayerViewHolder) {
                activePlayerViewHolder = holder
                val song = currentPlayingSong
                val player = mediaPlayer

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
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                )

                updateRepeatButtonUI(holder.btnRepeat)
                updateShuffleButtonUI(holder.btnShuffle)

                holder.btnRepeat.setOnClickListener {
                    isRepeatOne = !isRepeatOne
                    updateRepeatButtonUI(holder.btnRepeat)
                    val msg = if (isRepeatOne) "Modo: En bucle la misma canción" else "Modo: Siguiente canción automáticamente"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }

                holder.btnShuffle.setOnClickListener {
                    isShuffleEnabled = !isShuffleEnabled
                    updateShuffleButtonUI(holder.btnShuffle)
                    val msg = if (isShuffleEnabled) "Modo: Reproducción al azar" else "Modo: Reproducción lineal"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }

                holder.btnPlayPause.setOnClickListener {
                    togglePlayPause()
                }

                holder.btnPrevious.setOnClickListener {
                    playPreviousSong()
                }

                holder.btnNext.setOnClickListener {
                    playNextSong()
                }

                setupForwardTouchListener(holder.btnForward5)
                setupRewindTouchListener(holder.btnRewind5)

                holder.btnBackToList.setOnClickListener {
                    viewPager.setCurrentItem(0, true)
                }

                // Control del SeekBar para arrastrar y adelantar/retroceder la canción
                holder.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            holder.tvCurrentTime.text = formatDuration(progress.toLong())
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        isUserSeeking = true
                        viewPager.isUserInputEnabled = false
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val targetPosition = seekBar?.progress ?: 0
                        mediaPlayer?.seekTo(targetPosition)
                        holder.tvCurrentTime.text = formatDuration(targetPosition.toLong())
                        isUserSeeking = false
                        viewPager.isUserInputEnabled = true
                        currentPlayingSong?.let { saveLastPlayedSong(it, targetPosition) }
                    }
                })
            } else if (holder is PlaylistsViewHolder) {
                activePlaylistsViewHolder = holder
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                var isExpanded = prefs.getBoolean(KEY_PLAYLISTS_EXPANDED, true)

                fun updateExpandState(expanded: Boolean) {
                    holder.containerPlaylists.visibility = if (expanded) View.VISIBLE else View.GONE
                    holder.ivExpandArrow.rotation = if (expanded) 0f else -90f
                }

                updateExpandState(isExpanded)

                holder.btnTogglePlaylists.setOnClickListener {
                    isExpanded = !isExpanded
                    updateExpandState(isExpanded)
                    prefs.edit().putBoolean(KEY_PLAYLISTS_EXPANDED, isExpanded).apply()
                }

                holder.btnNewPlaylist.setOnClickListener {
                    showCreatePlaylistDialog()
                }

                holder.btnBackToPlaylistsMain.setOnClickListener {
                    activeOpenedPlaylist = null
                    holder.viewPlaylistDetail.visibility = View.GONE
                    holder.viewPlaylistsMain.visibility = View.VISIBLE
                    notifyItemChanged(2)
                }

                // Restaurar vista detalle de lista si ya estaba abierta
                if (activeOpenedPlaylist != null) {
                    val openedPl = playlistsList.find { it.name == activeOpenedPlaylist?.name }
                    if (openedPl != null) {
                        holder.viewPlaylistsMain.visibility = View.GONE
                        holder.viewPlaylistDetail.visibility = View.VISIBLE
                        holder.tvSelectedPlaylistTitle.text = openedPl.name
                        updatePlaylistSongsDetailList(holder, openedPl)
                    } else {
                        activeOpenedPlaylist = null
                        holder.viewPlaylistDetail.visibility = View.GONE
                        holder.viewPlaylistsMain.visibility = View.VISIBLE
                    }
                } else {
                    holder.viewPlaylistDetail.visibility = View.GONE
                    holder.viewPlaylistsMain.visibility = View.VISIBLE
                }

                if (playlistsList.isEmpty()) {
                    holder.tvEmptyPlaylists.visibility = View.VISIBLE
                    holder.lvPlaylists.visibility = View.GONE
                } else {
                    holder.tvEmptyPlaylists.visibility = View.GONE
                    holder.lvPlaylists.visibility = View.VISIBLE

                    val playlistAdapter = object : ArrayAdapter<Playlist>(this@MainActivity, R.layout.item_playlist, R.id.tvPlaylistName, playlistsList) {
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
                            val isPlaylistPlaying = playlist != null && currentPlayingPlaylist != null && playlist.name == currentPlayingPlaylist?.name

                            if (isPlaylistPlaying) {
                                tvName.setTextColor(pinkColor)
                                ivIcon?.setColorFilter(pinkColor)
                            } else {
                                tvName.setTextColor(Color.WHITE)
                                ivIcon?.setColorFilter(Color.WHITE)
                            }

                            rowView.setOnClickListener {
                                playlist?.let { pl ->
                                    val isAlreadyPlayingThisPlaylist = (currentPlayingPlaylist?.name == pl.name)
                                    activeOpenedPlaylist = pl

                                    if (!isAlreadyPlayingThisPlaylist) {
                                        currentPlayingPlaylist = pl
                                        val allSongs = getSongsList()
                                        val playlistSongs = allSongs.filter { pl.songIds.contains(it.id) }
                                        if (playlistSongs.isNotEmpty()) {
                                            playSong(playlistSongs[0], 0, pl)
                                        }
                                    }

                                    holder.viewPlaylistsMain.visibility = View.GONE
                                    holder.viewPlaylistDetail.visibility = View.VISIBLE
                                    holder.tvSelectedPlaylistTitle.text = pl.name

                                    updatePlaylistSongsDetailList(holder, pl)
                                    notifyDataSetChanged()
                                }
                            }

                            btnDelete.setOnClickListener {
                                playlist?.let { pl ->
                                    if (currentPlayingPlaylist == pl) {
                                        currentPlayingPlaylist = null
                                    }
                                    if (activeOpenedPlaylist == pl) {
                                        activeOpenedPlaylist = null
                                        holder.viewPlaylistDetail.visibility = View.GONE
                                        holder.viewPlaylistsMain.visibility = View.VISIBLE
                                    }
                                    playlistsList.remove(pl)
                                    savePlaylistsToPrefs()
                                    notifyDataSetChanged()
                                    if (playlistsList.isEmpty()) {
                                        holder.tvEmptyPlaylists.visibility = View.VISIBLE
                                        holder.lvPlaylists.visibility = View.GONE
                                    }
                                    Toast.makeText(this@MainActivity, "Lista '${pl.name}' eliminada", Toast.LENGTH_SHORT).show()
                                }
                            }

                            return rowView
                        }
                    }

                    holder.lvPlaylists.adapter = playlistAdapter
                    holder.lvPlaylists.setOnItemClickListener { _, _, plPos, _ ->
                        val clickedPlaylist = playlistsList[plPos]
                        val isAlreadyPlayingThisPlaylist = (currentPlayingPlaylist?.name == clickedPlaylist.name)

                        activeOpenedPlaylist = clickedPlaylist

                        if (!isAlreadyPlayingThisPlaylist) {
                            currentPlayingPlaylist = clickedPlaylist
                            val allSongs = getSongsList()
                            val playlistSongs = allSongs.filter { clickedPlaylist.songIds.contains(it.id) }
                            if (playlistSongs.isNotEmpty()) {
                                playSong(playlistSongs[0], 0, clickedPlaylist)
                            }
                        }

                        holder.viewPlaylistsMain.visibility = View.GONE
                        holder.viewPlaylistDetail.visibility = View.VISIBLE
                        holder.tvSelectedPlaylistTitle.text = clickedPlaylist.name

                        updatePlaylistSongsDetailList(holder, clickedPlaylist)
                        playlistAdapter.notifyDataSetChanged()
                    }
                }
            }
        }

        private fun updatePlaylistSongsDetailList(holder: PlaylistsViewHolder, playlist: Playlist) {
            val allSongs = getSongsList()
            val playlistSongs = allSongs.filter { playlist.songIds.contains(it.id) }

            if (playlistSongs.isEmpty()) {
                holder.tvEmptyPlaylistDetail.visibility = View.VISIBLE
                holder.lvPlaylistSongsDetail.visibility = View.GONE
            } else {
                holder.tvEmptyPlaylistDetail.visibility = View.GONE
                holder.lvPlaylistSongsDetail.visibility = View.VISIBLE

                val detailAdapter = object : ArrayAdapter<Song>(this@MainActivity, R.layout.item_song, R.id.tvSongTitle, playlistSongs) {
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
                            song?.let { s -> showSongOptionsMenuDialog(s) }
                        }

                        rowView.setOnClickListener {
                            song?.let { s ->
                                playSong(s, 0, playlist)
                                notifyDataSetChanged()
                            }
                        }

                        val isSelected = song != null && currentPlayingSong != null && song.id == currentPlayingSong?.id
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

                holder.lvPlaylistSongsDetail.adapter = detailAdapter
                holder.lvPlaylistSongsDetail.setOnItemClickListener { _, _, songPos, _ ->
                    val clickedSong = playlistSongs[songPos]
                    playSong(clickedSong, 0, playlist)
                    detailAdapter.notifyDataSetChanged()
                }
            }
        }

        private fun updateRepeatButtonUI(btn: ImageButton) {
            val activeColor = Color.parseColor("#F7CDDA")
            val inactiveColor = Color.parseColor("#555566")

            if (isRepeatOne) {
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
            if (isShuffleEnabled) {
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
                    setPlaybackSpeed(2.0f)
                    toast2x = Toast.makeText(this@MainActivity, "Velocidad 2x", Toast.LENGTH_SHORT)
                    toast2x?.show()
                }

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            isLongPress = false
                            progressHandler.postDelayed(longPressRunnable, 400)
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            progressHandler.removeCallbacks(longPressRunnable)
                            if (isLongPress) {
                                setPlaybackSpeed(1.0f)
                                toast2x?.cancel()
                            } else {
                                forward5Seconds()
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
                private val rewindLoopRunnable = object : Runnable {
                    override fun run() {
                        if (isLongPress) {
                            rewind5Seconds()
                            progressHandler.postDelayed(this, 200)
                        }
                    }
                }
                private val longPressRunnable = Runnable {
                    isLongPress = true
                    progressHandler.post(rewindLoopRunnable)
                    Toast.makeText(this@MainActivity, "Retroceso rápido", Toast.LENGTH_SHORT).show()
                }

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            isLongPress = false
                            progressHandler.postDelayed(longPressRunnable, 400)
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            progressHandler.removeCallbacks(longPressRunnable)
                            progressHandler.removeCallbacks(rewindLoopRunnable)
                            if (!isLongPress) {
                                rewind5Seconds()
                                v.performClick()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        inner class SongsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
            val lvSongs: ListView = view.findViewById(R.id.lvSongs)
        }

        inner class PlayerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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

        inner class PlaylistsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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
}
