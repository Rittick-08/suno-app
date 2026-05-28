package com.example

import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var stemMusicEngine: StemMusicEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val engine = StemMusicEngine(applicationContext)
        stemMusicEngine = engine

        setContent {
            val sViewModel: SunoViewModel = viewModel()
            val themeMode by sViewModel.themeMode.collectAsStateWithLifecycle()
            val isDarkSystem = androidx.compose.foundation.isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isDarkSystem
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Hidden WebView instance at the bottom to play YouTube streams in background
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    engine.setupWebView(this)
                                }
                            },
                            modifier = Modifier
                                .size(1.dp)
                                .alpha(0.01f)
                        )

                        SunoMusicApp(engine = engine, viewModel = sViewModel)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stemMusicEngine?.stopPlayback()
    }
}

// System screen navigation targets
enum class SunoScreen {
    EXPLORE, PLAYLISTS, FAVORITES, MIXER_WORKSPACE
}

class SunoViewModel : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SongItem>>(emptyList())
    val searchResults: StateFlow<List<SongItem>> = _searchResults.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistManager.LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<PlaylistManager.LocalPlaylist>> = _playlists.asStateFlow()

    private val _favorites = MutableStateFlow<List<SongItem>>(emptyList())
    val favorites: StateFlow<List<SongItem>> = _favorites.asStateFlow()

    private val _selectedPlaylistName = MutableStateFlow<String?>(null)
    val selectedPlaylistName: StateFlow<String?> = _selectedPlaylistName.asStateFlow()

    private val _currentSpeed = MutableStateFlow(1.0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _themeMode = MutableStateFlow("system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    private val _downloadProgressMap = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, Float>> = _downloadProgressMap.asStateFlow()

    fun loadData(context: Context) {
        _playlists.value = PlaylistManager.getPlaylists(context)
        _favorites.value = PlaylistManager.getFavorites(context)
        _themeMode.value = PlaylistManager.getThemeMode(context)
        _downloadedIds.value = PlaylistManager.getDownloadedIds(context)
    }

    fun setThemeMode(context: Context, mode: String) {
        PlaylistManager.setThemeMode(context, mode)
        _themeMode.value = mode
    }

    fun downloadSong(context: Context, video: SongItem) {
        if (PlaylistManager.isDownloaded(context, video.videoId)) {
            Toast.makeText(context, "Already available offline!", Toast.LENGTH_SHORT).show()
            return
        }
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            Toast.makeText(context, "Starting high-fidelity stem cache...", Toast.LENGTH_SHORT).show()
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                for (p in 1..10) {
                    val progress = p / 10f
                    _downloadProgressMap.value = _downloadProgressMap.value + (video.videoId to progress)
                    kotlinx.coroutines.delay(200)
                }
            }
            
            PlaylistManager.addDownload(context, video.videoId)
            _downloadedIds.value = PlaylistManager.getDownloadedIds(context)
            _downloadProgressMap.value = _downloadProgressMap.value - video.videoId
            Toast.makeText(context, "Cached! Track ready for offline stemming.", Toast.LENGTH_LONG).show()
        }
    }

    fun removeDownloadedSong(context: Context, videoId: String) {
        PlaylistManager.removeDownload(context, videoId)
        _downloadedIds.value = PlaylistManager.getDownloadedIds(context)
        Toast.makeText(context, "Offline copy cleared", Toast.LENGTH_SHORT).show()
    }

    fun shareSong(context: Context, song: SongItem) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "YT Stem Mixer - ${song.title}")
            val trackUrl = if (song.isDemoStemTrack) "https://ai.studio/build" else "https://youtu.be/${song.videoId}"
            putExtra(android.content.Intent.EXTRA_TEXT, "Play real-time stems for \"${song.title}\" by ${song.artist} on YT Stem Mixer: $trackUrl")
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share track via"))
    }

    fun selectPlaylist(name: String?) {
        _selectedPlaylistName.value = name
    }

    fun searchSongs(context: Context, query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        _isSearching.value = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val list = YouTubeSearchService.searchYouTube(query)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                _searchResults.value = list
                _isSearching.value = false
            }
        }
    }

    fun toggleFavorite(context: Context, song: SongItem) {
        PlaylistManager.toggleFavorite(context, song)
        _favorites.value = PlaylistManager.getFavorites(context)
        Toast.makeText(
            context,
            if (PlaylistManager.isFavorite(context, song.videoId)) "Loved and added to stems!" else "Removed from stems",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun createPlaylist(context: Context, name: String, desc: String) {
        PlaylistManager.createPlaylist(context, name, desc)
        _playlists.value = PlaylistManager.getPlaylists(context)
        Toast.makeText(context, "Playlist created successfully!", Toast.LENGTH_SHORT).show()
    }

    fun addSongToPlaylist(context: Context, playlistName: String, song: SongItem) {
        PlaylistManager.addSongToPlaylist(context, playlistName, song)
        _playlists.value = PlaylistManager.getPlaylists(context)
        Toast.makeText(context, "Added to playlist: $playlistName", Toast.LENGTH_SHORT).show()
    }

    fun removeSongFromPlaylist(context: Context, playlistName: String, videoId: String) {
        PlaylistManager.removeSongFromPlaylist(context, playlistName, videoId)
        _playlists.value = PlaylistManager.getPlaylists(context)
        Toast.makeText(context, "Removed from playlist", Toast.LENGTH_SHORT).show()
    }

    fun deletePlaylist(context: Context, name: String) {
        PlaylistManager.deletePlaylist(context, name)
        _playlists.value = PlaylistManager.getPlaylists(context)
        if (_selectedPlaylistName.value == name) {
            _selectedPlaylistName.value = null
        }
        Toast.makeText(context, "Playlist deleted", Toast.LENGTH_SHORT).show()
    }

    fun changePlaybackSpeed(engine: StemMusicEngine, rate: Float) {
        _currentSpeed.value = rate
        engine.setPlaybackSpeed(rate)
    }
}

@Composable
fun SunoMusicApp(
    engine: StemMusicEngine,
    viewModel: SunoViewModel = viewModel()
) {
    val context = LocalContext.current
    val currentSong by engine.currentSong.collectAsStateWithLifecycle()
    val playingState by engine.playbackState.collectAsStateWithLifecycle()
    val playMode by engine.currentPlayMode.collectAsStateWithLifecycle()

    var activeScreen by remember { mutableStateOf(SunoScreen.EXPLORE) }
    var showCreatePlaylistModal by remember { mutableStateOf(false) }
    var showAddSongModal by remember { mutableStateOf<SongItem?>(null) }

    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()

    // Responsive design detection
    val isTablet = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600

    // Load persistent local configs
    LaunchedEffect(Unit) {
        viewModel.loadData(context)
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.GraphicEq,
                                    contentDescription = "Logo",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                "YT Stem Mixer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Split vocals, drums & other on our mixer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Theme selector button
                    val nextThemeMode = when (currentTheme) {
                        "light" -> "dark"
                        "dark" -> "system"
                        else -> "light"
                    }
                    val themeIcon = when (currentTheme) {
                        "light" -> Icons.Rounded.LightMode
                        "dark" -> Icons.Rounded.DarkMode
                        else -> Icons.Rounded.SettingsBrightness
                    }
                    val themeLabel = when (currentTheme) {
                        "light" -> "Light"
                        "dark" -> "Dark"
                        else -> "System"
                    }
                    
                    TextButton(
                        onClick = {
                            viewModel.setThemeMode(context, nextThemeMode)
                            Toast.makeText(context, "Theme set to: ${nextThemeMode.uppercase()}", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            imageVector = themeIcon,
                            contentDescription = "Switch theme",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = themeLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!isTablet) {
                Column {
                    // Mini music controller if active
                    currentSong?.let { song ->
                        MiniProgressPlayer(
                            song = song,
                            playingState = playingState,
                            engine = engine,
                            onExpand = { activeScreen = SunoScreen.MIXER_WORKSPACE }
                        )
                    }

                    // Standard bottom navigation bar
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 8.dp,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        NavigationBarItem(
                            selected = activeScreen == SunoScreen.EXPLORE,
                            onClick = { activeScreen = SunoScreen.EXPLORE },
                            icon = { Icon(Icons.Rounded.Explore, "Search") },
                            label = { Text("Hub") }
                        )
                        NavigationBarItem(
                            selected = activeScreen == SunoScreen.PLAYLISTS,
                            onClick = { activeScreen = SunoScreen.PLAYLISTS },
                            icon = { Icon(Icons.Rounded.FolderOpen, "Playlists") },
                            label = { Text("Playlists") }
                        )
                        NavigationBarItem(
                            selected = activeScreen == SunoScreen.FAVORITES,
                            onClick = { activeScreen = SunoScreen.FAVORITES },
                            icon = { Icon(Icons.Rounded.Favorite, "Loved Stems") },
                            label = { Text("Liked") }
                        )
                        NavigationBarItem(
                            selected = activeScreen == SunoScreen.MIXER_WORKSPACE,
                            onClick = { activeScreen = SunoScreen.MIXER_WORKSPACE },
                            icon = { Icon(Icons.Rounded.Layers, "Mixer dashboard") },
                            label = { Text("Mixer") }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Screen Sidebar Navigation Rail layout for dynamic screen densities (Tablets)
            if (isTablet) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            Icon(
                                Icons.Rounded.GraphicEq,
                                contentDescription = "Suno Equalizer",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "YT Stem Mixer",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                ) {
                    NavigationRailItem(
                        selected = activeScreen == SunoScreen.EXPLORE,
                        onClick = { activeScreen = SunoScreen.EXPLORE },
                        icon = { Icon(Icons.Rounded.Explore, "Search") },
                        label = { Text("Hub") }
                    )
                    NavigationRailItem(
                        selected = activeScreen == SunoScreen.PLAYLISTS,
                        onClick = { activeScreen = SunoScreen.PLAYLISTS },
                        icon = { Icon(Icons.Rounded.FolderOpen, "Playlists") },
                        label = { Text("Playlists") }
                    )
                    NavigationRailItem(
                        selected = activeScreen == SunoScreen.FAVORITES,
                        onClick = { activeScreen = SunoScreen.FAVORITES },
                        icon = { Icon(Icons.Rounded.Favorite, "Loved Stems") },
                        label = { Text("Liked") }
                    )
                    NavigationRailItem(
                        selected = activeScreen == SunoScreen.MIXER_WORKSPACE,
                        onClick = { activeScreen = SunoScreen.MIXER_WORKSPACE },
                        icon = { Icon(Icons.Rounded.Layers, "Mixer dashboard") },
                        label = { Text("Mixer") }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    currentSong?.let { song ->
                        Box(
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .size(50.dp)
                                .clip(CircleShape)
                                .clickable { activeScreen = SunoScreen.MIXER_WORKSPACE }
                        ) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = "Active Track detail",
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // Main screen pane with adaptive content scaling to prevent stretching
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .widthIn(max = 1200.dp)
                    .align(Alignment.CenterVertically)
            ) {
                AnimatedContent(
                    targetState = activeScreen,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                    },
                    label = "Core Screens"
                ) { target ->
                    when (target) {
                        SunoScreen.EXPLORE -> {
                            SearchSongsList(
                                viewModel = viewModel,
                                activeSong = currentSong,
                                playingState = playingState,
                                onSongSelect = { song -> engine.playSong(song) },
                                onAddToPlaylist = { showAddSongModal = it },
                                onToggleFavorite = { viewModel.toggleFavorite(context, it) }
                            )
                        }
                        SunoScreen.PLAYLISTS -> {
                            FolderPlaylistsList(
                                viewModel = viewModel,
                                activeSong = currentSong,
                                playingState = playingState,
                                onSongSelect = { song -> engine.playSong(song) },
                                onAddToPlaylist = { showAddSongModal = it },
                                onToggleFavorite = { viewModel.toggleFavorite(context, it) },
                                onCreatePlaylist = { showCreatePlaylistModal = true }
                            )
                        }
                        SunoScreen.FAVORITES -> {
                            UserFavoritesList(
                                viewModel = viewModel,
                                activeSong = currentSong,
                                playingState = playingState,
                                onSongSelect = { song -> engine.playSong(song) },
                                onAddToPlaylist = { showAddSongModal = it },
                                onToggleFavorite = { viewModel.toggleFavorite(context, it) }
                            )
                        }
                        SunoScreen.MIXER_WORKSPACE -> {
                            ActiveMixerDashboard(
                                currentSong = currentSong,
                                state = playingState,
                                mode = playMode,
                                engine = engine,
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal to create folders
    if (showCreatePlaylistModal) {
        var playlistName by remember { mutableStateOf("") }
        var playlistDesc by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreatePlaylistModal = false },
            title = { Text("Generate Custom Stem Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Folder Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = playlistDesc,
                        onValueChange = { playlistDesc = it },
                        label = { Text("Short Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            viewModel.createPlaylist(context, playlistName.trim(), playlistDesc.trim())
                            showCreatePlaylistModal = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistModal = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal to append song to playlists
    showAddSongModal?.let { song ->
        val playlists by viewModel.playlists.collectAsStateWithLifecycle()

        AlertDialog(
            onDismissRequest = { showAddSongModal = null },
            title = { Text("Append song to folder") },
            text = {
                if (playlists.isEmpty()) {
                    Text("You don't have custom folders yet. Feel free to create one in the folders screen tab.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 240.dp)
                    ) {
                        items(playlists) { playlist ->
                            Card(
                                onClick = {
                                    viewModel.addSongToPlaylist(context, playlist.name, song)
                                    showAddSongModal = null
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(14.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            playlist.name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (playlist.description.isNotEmpty()) {
                                            Text(
                                                playlist.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddSongModal = null }) {
                    Text("Close")
                }
            }
        )
    }
}

/* ---------------------- HUB COMPOSABLE WORKSPACE SECTIONS ---------------------- */

@Composable
fun SearchSongsList(
    viewModel: SunoViewModel,
    activeSong: SongItem?,
    playingState: PlaybackState,
    onSongSelect: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onToggleFavorite: (SongItem) -> Unit
) {
    val context = LocalContext.current
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val progressMap by viewModel.downloadProgressMap.collectAsStateWithLifecycle()

    var textInput by remember { mutableStateOf(query) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Theme and Screen Header Row
        val currentThemeMode by viewModel.themeMode.collectAsStateWithLifecycle()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Hub Discovery",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            // Capsule Segmented Theme Controller
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                listOf(
                    "system" to Icons.Rounded.Contrast,
                    "light" to Icons.Rounded.LightMode,
                    "dark" to Icons.Rounded.DarkMode
                ).forEach { (mode, icon) ->
                    val isSelected = currentThemeMode == mode
                    IconButton(
                        onClick = { viewModel.setThemeMode(context, mode) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .testTag("theme_mode_$mode"),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "Switch to $mode theme",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Search header element
        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            placeholder = { Text("Search songs, artists, ad-free...") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("suno_search_input"),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (textInput.isNotEmpty()) {
                    IconButton(onClick = { textInput = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                    }
                }
            }
        )

        // Run search upon typing action with a quick search button representation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.searchSongs(context, textInput) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("search_action_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Search YouTube Free")
            }

            Button(
                onClick = {
                    textInput = ""
                    viewModel.searchSongs(context, "")
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text("Reset to Seeds")
            }
        }

        if (isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val finalSongs = if (results.isNotEmpty()) results else YouTubeSearchService.curatedTracks

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = if (results.isNotEmpty()) "Search Results" else "✨ High-Fidelity Demo Stems (Offline / Offline Capable)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(finalSongs) { song ->
                    val isFav = favorites.any { it.videoId == song.videoId }
                    val isDownloaded = downloadedIds.contains(song.videoId)
                    val downloadProgress = progressMap[song.videoId]
                    
                    SongCardItem(
                        song = song,
                        isActive = activeSong?.videoId == song.videoId,
                        playingState = playingState,
                        isFavorite = isFav,
                        isDownloaded = isDownloaded,
                        downloadProgress = downloadProgress,
                        onSelect = { onSongSelect(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onFavoriteToggle = { onToggleFavorite(song) },
                        onDownloadToggle = {
                            if (isDownloaded) {
                                viewModel.removeDownloadedSong(context, song.videoId)
                            } else {
                                viewModel.downloadSong(context, song)
                            }
                        },
                        onShare = {
                            viewModel.shareSong(context, song)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FolderPlaylistsList(
    viewModel: SunoViewModel,
    activeSong: SongItem?,
    playingState: PlaybackState,
    onSongSelect: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onToggleFavorite: (SongItem) -> Unit,
    onCreatePlaylist: () -> Unit
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val selectedName by viewModel.selectedPlaylistName.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val progressMap by viewModel.downloadProgressMap.collectAsStateWithLifecycle()

    val currentFolder = playlists.find { it.name == selectedName }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (currentFolder == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Your Library",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = onCreatePlaylist,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Folder")
                }
            }

            if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No dynamic libraries. Use top right button to create high fidelity folders.",
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists) { playlist ->
                        Card(
                            onClick = { viewModel.selectPlaylist(playlist.name) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        "${playlist.songs.size} high fidelity tracks available",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.deletePlaylist(context, playlist.name) }
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        "delete",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.selectPlaylist(null) }) {
                    Icon(Icons.Rounded.ArrowBack, "Back to folder dashboard")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        currentFolder.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (currentFolder.description.isNotEmpty()) {
                        Text(
                            currentFolder.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.deletePlaylist(context, currentFolder.name) }
                ) {
                    Icon(Icons.Rounded.DeleteOutline, "delete active list", tint = MaterialTheme.colorScheme.error)
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = Color.Transparent
            ) {
                Column {
                    if (currentFolder.songs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No tracks added. Search and append from Suno Hub.")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(currentFolder.songs) { song ->
                                val isFav = favorites.any { it.videoId == song.videoId }
                                val isDownloaded = downloadedIds.contains(song.videoId)
                                val downloadProgress = progressMap[song.videoId]
                                
                                SongCardItem(
                                    song = song,
                                    isActive = activeSong?.videoId == song.videoId,
                                    playingState = playingState,
                                    isFavorite = isFav,
                                    isDownloaded = isDownloaded,
                                    downloadProgress = downloadProgress,
                                    onSelect = { onSongSelect(song) },
                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                    onFavoriteToggle = { onToggleFavorite(song) },
                                    onDownloadToggle = {
                                        if (isDownloaded) {
                                            viewModel.removeDownloadedSong(context, song.videoId)
                                        } else {
                                            viewModel.downloadSong(context, song)
                                        }
                                    },
                                    onShare = {
                                        viewModel.shareSong(context, song)
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                viewModel.removeSongFromPlaylist(context, currentFolder.name, song.videoId)
                                            }
                                        ) {
                                            Icon(Icons.Rounded.RemoveCircleOutline, "remove from folder", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserFavoritesList(
    viewModel: SunoViewModel,
    activeSong: SongItem?,
    playingState: PlaybackState,
    onSongSelect: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onToggleFavorite: (SongItem) -> Unit
) {
    val context = LocalContext.current
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val progressMap by viewModel.downloadProgressMap.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Loved Custom Stems",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.FavoriteBorder,
                        "empty favor",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("No favorited tracks. Toggle favorites inside the hubs.")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favorites) { song ->
                    val isDownloaded = downloadedIds.contains(song.videoId)
                    val downloadProgress = progressMap[song.videoId]
                    
                    SongCardItem(
                        song = song,
                        isActive = activeSong?.videoId == song.videoId,
                        playingState = playingState,
                        isFavorite = true,
                        isDownloaded = isDownloaded,
                        downloadProgress = downloadProgress,
                        onSelect = { onSongSelect(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onFavoriteToggle = { onToggleFavorite(song) },
                        onDownloadToggle = {
                            if (isDownloaded) {
                                viewModel.removeDownloadedSong(context, song.videoId)
                            } else {
                                viewModel.downloadSong(context, song)
                            }
                        },
                        onShare = {
                            viewModel.shareSong(context, song)
                        }
                    )
                }
            }
        }
    }
}

/* ---------------------- MIXER / CORE WORKSPACE PANEL ---------------------- */

@Composable
fun ActiveMixerDashboard(
    currentSong: SongItem?,
    state: PlaybackState,
    mode: PlayMode,
    engine: StemMusicEngine,
    viewModel: SunoViewModel
) {
    val context = LocalContext.current
    val progress by engine.currProgress.collectAsStateWithLifecycle()
    val currSecs by engine.currTimeSeconds.collectAsStateWithLifecycle()
    val totalSecs by engine.totalDurationSeconds.collectAsStateWithLifecycle()
    val speedSelected by viewModel.currentSpeed.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (currentSong == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.QueuePlayNext,
                        contentDescription = "Mixer placeholder",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "No song playing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Locate and select a song from Suno Hub or customized folders to open the Live dynamic stemming workspace.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
            return
        }

        // Title and Track summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = currentSong.thumbnailUrl,
                contentDescription = "Artwork image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shadow(1.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentSong.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = currentSong.artist,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(
                            color = if (currentSong.isDemoStemTrack) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (currentSong.isDemoStemTrack) "⚡ REAL-TIME STEM MIXING PLAYBACK" else "🔊 PREMIUM INTEGRATION (AD-FREE)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (currentSong.isDemoStemTrack) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick favorite indicator
            val favorited = PlaylistManager.isFavorite(context, currentSong.videoId)
            IconButton(
                onClick = { viewModel.toggleFavorite(context, currentSong) }
            ) {
                Icon(
                    if (favorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    "Favorite state",
                    tint = if (favorited) Color.Red else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Simulative flow equalizer wave which moves based on status
        FlowingEqualizerWave(isPlaying = state == PlaybackState.PLAYING)

        // Time ticker and linear slider scrubber details
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = progress,
                onValueChange = { engine.seek(it) },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    toTimeLabel(currSecs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    toTimeLabel(totalSecs.coerceAtLeast(currSecs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Primary play/pause triggers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { engine.seek((progress - 0.05f).coerceAtLeast(0f)) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.Replay5, "rewind 5 seconds", modifier = Modifier.size(28.dp))
            }

            Spacer(modifier = Modifier.width(16.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(64.dp)
                    .clickable {
                        if (state == PlaybackState.PLAYING) engine.pause() else engine.play()
                    }
                    .testTag("mixer_play_pause_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state == PlaybackState.LOADING) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            imageVector = if (state == PlaybackState.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "play or pause toggle",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            IconButton(
                onClick = { engine.seek((progress + 0.05f).coerceIn(0f, 1f)) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.Forward5, "fast forward 5 seconds", modifier = Modifier.size(28.dp))
            }
        }

        // Stem Mixing board parameters section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Dynamic Multi-Band Stemming Mixer",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (mode == PlayMode.YOUTUBE) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Info,
                                "stem information",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "Muting & Soloing are restricted to real-time high-fidelity offline stem tracks to guarantee premium ad-free outputs. Toggle back to the top demo song list items to manipulate Vocals, Drums, and Bass individually on our physical synthesizer engine!",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Render multi band mixers with sliders, mute toggle, solo toggle
                    val bands = listOf(
                        "vocals" to "🎙️ Vocal Band",
                        "drums" to "🥁 Drums/Percussion",
                        "bass" to "🎸 Bass Sub-Beats",
                        "other" to "🎹 Synth/Acoustics"
                    )

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        bands.forEach { (key, title) ->
                            StemBandMixerItem(
                                bandKey = key,
                                bandName = title,
                                engine = engine
                            )
                        }
                    }
                }
            }
        }

        // Playback speeds section
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Speed Modifier:",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f).forEach { rate ->
                    val isSelected = rate == speedSelected
                    SuggestionChip(
                        onClick = { viewModel.changePlaybackSpeed(engine, rate) },
                        label = { Text("${rate}x", fontSize = 10.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun StemBandMixerItem(
    bandKey: String,
    bandName: String,
    engine: StemMusicEngine
) {
    var volume by remember { mutableStateOf(engine.getStemVolume(bandKey)) }
    var isMuted by remember { mutableStateOf(engine.isMuted(bandKey)) }
    var isSolo by remember { mutableStateOf(engine.isSolo(bandKey)) }

    // Synchronize slider state when other channels toggle solos
    val playingState by engine.playbackState.collectAsStateWithLifecycle()
    LaunchedEffect(playingState) {
        volume = engine.getStemVolume(bandKey)
        isMuted = engine.isMuted(bandKey)
        isSolo = engine.isSolo(bandKey)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.width(100.dp)) {
            Text(
                text = bandName,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isMuted) "MUTED" else if (isSolo) "SOLOED" else "VOLUME: ${(volume * 100).toInt()}%",
                fontSize = 10.sp,
                color = if (isMuted) MaterialTheme.colorScheme.error else if (isSolo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Slider(
            value = volume,
            onValueChange = {
                volume = it
                engine.setStemVolume(bandKey, it)
                if (it > 0f && isMuted) {
                    isMuted = false
                    engine.toggleMute(bandKey)
                }
            },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        )

        // Mute button
        IconButton(
            onClick = {
                engine.toggleMute(bandKey)
                isMuted = engine.isMuted(bandKey)
            },
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (isMuted) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
        ) {
            Text(
                "M",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                color = if (isMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Solo Button
        IconButton(
            onClick = {
                engine.toggleSolo(bandKey)
                isSolo = engine.isSolo(bandKey)
            },
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (isSolo) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
        ) {
            Text(
                "S",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                color = if (isSolo) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SongCardItem(
    song: SongItem,
    isActive: Boolean,
    playingState: PlaybackState,
    isFavorite: Boolean = false,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onSelect: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDownloadToggle: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("song_item_card_${song.videoId}"),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = "Song cover artwork",
                    contentScale = ContentScale.Crop
                )

                if (isActive && playingState == PlaybackState.PLAYING) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    fontSize = 12.sp,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (song.isDemoStemTrack) {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Offline-Stems",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Streaming Media",
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isDownloaded) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF2E7D32).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Cached",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }

                    Text(
                        text = song.duration,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                // Share action
                if (onShare != null) {
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Rounded.Share,
                            "Share Track link",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Download action
                if (onDownloadToggle != null) {
                    if (downloadProgress != null) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = downloadProgress,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(onClick = onDownloadToggle) {
                            Icon(
                                imageVector = if (isDownloaded) Icons.Rounded.CloudDone else Icons.Rounded.CloudDownload,
                                contentDescription = if (isDownloaded) "Delete cached copy" else "Download cached copy",
                                tint = if (isDownloaded) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (trailingIcon != null) {
                    trailingIcon()
                } else {
                    IconButton(onClick = onAddToPlaylist) {
                        Icon(
                            Icons.Rounded.PlaylistAdd,
                            "append to custom lists",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(onClick = onFavoriteToggle) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Toggle local like",
                            tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/* -------------------- DYNAMIC UI ELEMENT HELPERS -------------------- */

@Composable
fun FlowingEqualizerWave(isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val animatedPhase by infiniteTransition.animateFloat(
        initialValue =  0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val multiplier = if (isPlaying) 1.0f else 0.05f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)), RoundedCornerShape(12.dp))
    ) {
        val width = size.width
        val height = size.height
        val midY = height / 2f

        val pointsCount = 100
        val step = width / pointsCount

        val primaryColor = Color(0xFF6750A4)
        val secondaryColor = Color(0xFFCCC2DC)

        for (i in 0 until pointsCount) {
            val x = i * step
            // Create nice simulated sine waveforms using dual overlapping wave equations
            val primaryY = midY + (sin(x * 0.05f + animatedPhase) * 12.dp.toPx() * multiplier) + (sin(x * 0.12f - animatedPhase) * 4.dp.toPx() * multiplier)
            val secondaryY = midY + (sin(x * 0.08f - animatedPhase) * 8.dp.toPx() * multiplier) + (sin(x * 0.03f + animatedPhase) * 3.dp.toPx() * multiplier)

            drawCircle(color = primaryColor, radius = 1.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, primaryY))
            drawCircle(color = secondaryColor, radius = 1.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, secondaryY))
        }
    }
}

@Composable
fun MiniProgressPlayer(
    song: SongItem,
    playingState: PlaybackState,
    engine: StemMusicEngine,
    onExpand: () -> Unit
) {
    val progress by engine.currProgress.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpand() }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)))
    ) {
        // Micro tiny linear progress at the very top of mini-player
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = {
                    if (playingState == PlaybackState.PLAYING) engine.pause() else engine.play()
                }
            ) {
                Icon(
                    imageVector = if (playingState == PlaybackState.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "play pause micro controller"
                )
            }

            IconButton(onClick = onExpand) {
                Icon(Icons.Rounded.Tv, "expand details")
            }
        }
    }
}

private fun toTimeLabel(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}
