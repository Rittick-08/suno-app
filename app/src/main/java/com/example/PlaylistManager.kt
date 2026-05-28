package com.example

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object PlaylistManager {
    private const val PREFS_NAME = "yt_stem_music_prefs"
    private const val KEY_PLAYLISTS = "user_playlists"
    private const val KEY_FAVORITES = "user_favorites"
    private const val KEY_THEME = "app_theme_mode" // "system", "light", "dark"
    private const val KEY_DOWNLOADS = "user_downloads"
    private const val TAG = "PlaylistManager"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val songListAdapter = moshi.adapter<List<SongItem>>(
        Types.newParameterizedType(List::class.java, SongItem::class.java)
    )

    // Data class representing a customizable local playlist
    data class LocalPlaylist(
        val name: String,
        val description: String = "",
        val songs: List<SongItem> = emptyList(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val playlistListAdapter = moshi.adapter<List<LocalPlaylist>>(
        Types.newParameterizedType(List::class.java, LocalPlaylist::class.java)
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieve stored theme mode preference: "system", "light", or "dark"
     */
    fun getThemeMode(context: Context): String {
        return getPrefs(context).getString(KEY_THEME, "system") ?: "system"
    }

    /**
     * Save stored theme mode preference
     */
    fun setThemeMode(context: Context, theme: String) {
        getPrefs(context).edit().putString(KEY_THEME, theme).apply()
    }

    /**
     * Get unique video IDs of all downloaded tracks
     */
    fun getDownloadedIds(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_DOWNLOADS, emptySet()) ?: emptySet()
    }

    /**
     * Store list of downloaded video IDs
     */
    fun saveDownloadedIds(context: Context, ids: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_DOWNLOADS, ids).apply()
    }

    /**
     * Add a download tracker
     */
    fun addDownload(context: Context, videoId: String) {
        val ids = getDownloadedIds(context).toMutableSet()
        ids.add(videoId)
        saveDownloadedIds(context, ids)
    }

    /**
     * Remove download tracker
     */
    fun removeDownload(context: Context, videoId: String) {
        val ids = getDownloadedIds(context).toMutableSet()
        ids.remove(videoId)
        saveDownloadedIds(context, ids)
    }

    /**
     * Check if a video is downloaded
     */
    fun isDownloaded(context: Context, videoId: String): Boolean {
        return getDownloadedIds(context).contains(videoId)
    }

    /**
     * Get all favorite songs
     */
    fun getFavorites(context: Context): List<SongItem> {
        val json = getPrefs(context).getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            songListAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing favorites", e)
            emptyList()
        }
    }

    /**
     * Save/Update favorite songs list
     */
    fun saveFavorites(context: Context, songs: List<SongItem>) {
        val json = songListAdapter.toJson(songs)
        getPrefs(context).edit().putString(KEY_FAVORITES, json).apply()
    }

    /**
     * Set a song as liked/disliked
     */
    fun toggleFavorite(context: Context, song: SongItem): Boolean {
        val favorites = getFavorites(context).toMutableList()
        val existingIndex = favorites.indexOfFirst { it.videoId == song.videoId }
        val isAdded: Boolean
        if (existingIndex >= 0) {
            favorites.removeAt(existingIndex)
            isAdded = false
        } else {
            favorites.add(song)
            isAdded = true
        }
        saveFavorites(context, favorites)
        return isAdded
    }

    fun isFavorite(context: Context, videoId: String): Boolean {
        return getFavorites(context).any { it.videoId == videoId }
    }

    /**
     * Retrieve all custom playlists
     */
    fun getPlaylists(context: Context): List<LocalPlaylist> {
        val json = getPrefs(context).getString(KEY_PLAYLISTS, null) ?: return getDefaultPlaylists()
        return try {
            playlistListAdapter.fromJson(json) ?: getDefaultPlaylists()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing playlists", e)
            getDefaultPlaylists()
        }
    }

    /**
     * Save custom playlists list
     */
    fun savePlaylists(context: Context, playlists: List<LocalPlaylist>) {
        val json = playlistListAdapter.toJson(playlists)
        getPrefs(context).edit().putString(KEY_PLAYLISTS, json).apply()
    }

    /**
     * Append a playlist
     */
    fun createPlaylist(context: Context, name: String, description: String = "") {
        val playlists = getPlaylists(context).toMutableList()
        if (playlists.any { it.name.equals(name, ignoreCase = true) }) return // Avoid duplicates
        playlists.add(LocalPlaylist(name = name, description = description))
        savePlaylists(context, playlists)
    }

    /**
     * Add song to playlist
     */
    fun addSongToPlaylist(context: Context, playlistName: String, song: SongItem) {
        val playlists = getPlaylists(context).toMutableList()
        val index = playlists.indexOfFirst { it.name == playlistName }
        if (index >= 0) {
            val playlist = playlists[index]
            if (!playlist.songs.any { it.videoId == song.videoId }) {
                val updatedSongs = playlist.songs.toMutableList().apply { add(song) }
                playlists[index] = playlist.copy(songs = updatedSongs)
                savePlaylists(context, playlists)
            }
        }
    }

    /**
     * Delete playlist
     */
    fun deletePlaylist(context: Context, name: String) {
        val playlists = getPlaylists(context).toMutableList()
        playlists.removeAll { it.name == name }
        savePlaylists(context, playlists)
    }

    /**
     * Erase a song from a playlist
     */
    fun removeSongFromPlaylist(context: Context, playlistName: String, videoId: String) {
        val playlists = getPlaylists(context).toMutableList()
        val index = playlists.indexOfFirst { it.name == playlistName }
        if (index >= 0) {
            val playlist = playlists[index]
            val updatedSongs = playlist.songs.filter { it.videoId != videoId }
            playlists[index] = playlist.copy(songs = updatedSongs)
            savePlaylists(context, playlists)
        }
    }

    /**
     * Generate default seed playlists
     */
    private fun getDefaultPlaylists(): List<LocalPlaylist> {
        return listOf(
            LocalPlaylist(
                name = "🌌 Ambient Horizons",
                description = "Cosmic tracks, deep echoes, and atmospheric instrumentals.",
                songs = listOf(
                    YouTubeSearchService.curatedTracks[1], // Synthwave Chill
                    YouTubeSearchService.curatedTracks[2]  // Ambient Space
                )
            ),
            LocalPlaylist(
                name = "💻 Coding & Workflows",
                description = "High-concentration lofi and retro background beats to code to.",
                songs = listOf(
                    YouTubeSearchService.curatedTracks[0], // Lofi Girl
                    YouTubeSearchService.curatedTracks[1]  // Synthwave
                )
            ),
            LocalPlaylist(
                name = "🔥 Bass & Beats Heavy",
                description = "Intense metal and fast electro-house tracks for stemming experiments.",
                songs = listOf(
                    YouTubeSearchService.curatedTracks[3], // Heavy Rock
                    YouTubeSearchService.curatedTracks[4]  // Upbeat House
                )
            )
        )
    }
}
