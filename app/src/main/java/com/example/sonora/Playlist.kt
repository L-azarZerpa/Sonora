package com.example.sonora

data class Playlist(
    val name: String,
    val songIds: MutableList<Long> = mutableListOf()
)
