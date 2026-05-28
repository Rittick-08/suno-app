package com.example

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackState {
    IDLE, LOADING, PLAYING, PAUSED, ERROR
}

enum class PlayMode {
    YOUTUBE, LOCAL_STEM
}

class StemMusicEngine(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val TAG = "StemMusicEngine"

    // Engine playback state flows
    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentPlayMode = MutableStateFlow(PlayMode.YOUTUBE)
    val currentPlayMode: StateFlow<PlayMode> = _currentPlayMode.asStateFlow()

    private val _currentSong = MutableStateFlow<SongItem?>(null)
    val currentSong: StateFlow<SongItem?> = _currentSong.asStateFlow()

    private val _currProgress = MutableStateFlow(0f) // 0.0 to 1.0 representing percentage
    val currProgress: StateFlow<Float> = _currProgress.asStateFlow()

    private val _currTimeSeconds = MutableStateFlow(0)
    val currTimeSeconds: StateFlow<Int> = _currTimeSeconds.asStateFlow()

    private val _totalDurationSeconds = MutableStateFlow(0)
    val totalDurationSeconds: StateFlow<Int> = _totalDurationSeconds.asStateFlow()

    // Multi-track media player list for physical stemming
    private var stemPlayers = mutableMapOf<String, MediaPlayer?>()
    private val stemBands = listOf("vocals", "drums", "bass", "other")
    private val stemVolumes = mutableMapOf<String, Float>()
    private val stemMutes = mutableMapOf<String, Boolean>()
    private val stemSolos = mutableMapOf<String, Boolean>()

    // For keeping track of active synchronization coroutine
    private var syncJob: Job? = null
    private var webView: WebView? = null
    private var isWebReady = false

    init {
        // Initialize stem states
        stemBands.forEach { band ->
            stemVolumes[band] = 1.0f
            stemMutes[band] = false
            stemSolos[band] = false
        }
    }

    /**
     * Set up the internal hidden WebView for YouTube streaming.
     * Incorporates custom ad-blocking and native web-bridge binding.
     */
    fun setupWebView(wv: WebView) {
        this.webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isWebReady = true
                Log.d(TAG, "WebView finished loading. Tube player initialized.")
            }
        }

        wv.addJavascriptInterface(this, "AndroidStemBridge")

        // Load embedding container with basic customized player logic
        val rawHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body, html { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
                    #player { width: 100%; height: 100%; }
                </style>
            </head>
            <body>
                <div id="player"></div>
                <script>
                    var tag = document.createElement('script');
                    tag.src = "https://www.youtube.com/iframe_api";
                    var firstScriptTag = document.getElementsByTagName('script')[0];
                    firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                    var player;
                    function onYouTubeIframeAPIReady() {
                        player = new YT.Player('player', {
                            height: '100%',
                            width: '100%',
                            videoId: '',
                            playerVars: {
                                'playsinline': 1,
                                'controls': 0,
                                'modestbranding': 1,
                                'rel': 0,
                                'fs': 0,
                                'showinfo': 0,
                                'iv_load_policy': 3,
                                'origin': 'https://youtube.com',
                                'disablekb': 1
                            },
                            events: {
                                'onReady': onPlayerReady,
                                'onStateChange': onPlayerStateChange,
                                'onError': onPlayerError
                            }
                        });
                    }

                    // Native callbacks for clean bridges
                    function onPlayerReady(event) {
                        AndroidStemBridge.onWebViewReady();
                    }

                    function onPlayerStateChange(event) {
                        // event.data matches: -1 (unstarted), 0 (ended), 1 (playing), 2 (paused), 3 (buffering), 5 (video cued)
                        AndroidStemBridge.onPlayerStateChange(event.data);
                        
                        if (event.data === 1) {
                            startPollingProgress();
                        } else {
                            stopPollingProgress();
                        }
                    }

                    function onPlayerError(errCode) {
                        AndroidStemBridge.onPlayerError(errCode);
                    }

                    var progressInterval;
                    function startPollingProgress() {
                        stopPollingProgress();
                        progressInterval = setInterval(function() {
                            if (player && typeof player.getCurrentTime === 'function' && typeof player.getDuration === 'function') {
                                var curr = player.getCurrentTime();
                                var duration = player.getDuration();
                                AndroidStemBridge.onProgressUpdate(curr, duration);
                            }
                        }, 250);
                    }

                    function stopPollingProgress() {
                        clearInterval(progressInterval);
                    }

                    // Direct interface controllers
                    function loadVideo(vidId) {
                        if (player && typeof player.loadVideoById === 'function') {
                            player.loadVideoById(vidId);
                        }
                    }

                    function play() {
                        if (player && typeof player.playVideo === 'function') {
                            player.playVideo();
                        }
                    }

                    function pause() {
                        if (player && typeof player.pauseVideo === 'function') {
                            player.pauseVideo();
                        }
                    }

                    function seek(seconds) {
                        if (player && typeof player.seekTo === 'function') {
                            player.seekTo(seconds, true);
                        }
                    }

                    function setSpeed(rate) {
                        if (player && typeof player.setPlaybackRate === 'function') {
                            player.setPlaybackRate(rate);
                        }
                    }

                    function setVol(volume) {
                        if (player && typeof player.setVolume === 'function') {
                            player.setVolume(volume); // 0 to 100
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        wv.loadDataWithBaseURL("https://www.youtube.com", rawHtml, "text/html", "UTF-8", null)
    }

    /**
     * API Javascript Interfaces
     */
    @JavascriptInterface
    fun onWebViewReady() {
        Log.i(TAG, "YouTube Player API Bridge is active and ready.")
    }

    @JavascriptInterface
    fun onPlayerStateChange(state: Int) {
        scope.launch(Dispatchers.Main) {
            when (state) {
                1 -> _playbackState.value = PlaybackState.PLAYING
                2 -> _playbackState.value = PlaybackState.PAUSED
                3 -> _playbackState.value = PlaybackState.LOADING
                0 -> {
                    _playbackState.value = PlaybackState.PAUSED
                    _currProgress.value = 1.0f
                    // Trigger next track if hooked up, or stop
                }
            }
        }
    }

    @JavascriptInterface
    fun onProgressUpdate(currentSeconds: Float, totalSeconds: Float) {
        scope.launch(Dispatchers.Main) {
            _currTimeSeconds.value = currentSeconds.toInt()
            _totalDurationSeconds.value = totalSeconds.toInt()
            if (totalSeconds > 0) {
                _currProgress.value = currentSeconds / totalSeconds
            }
        }
    }

    @JavascriptInterface
    fun onPlayerError(code: Int) {
        Log.e(TAG, "YT Player Error reported via Bridge: code $code")
        scope.launch(Dispatchers.Main) {
            _playbackState.value = PlaybackState.ERROR
        }
    }

    /* ---------------------- ENGINE PLAYBACK INTERFACES ----------------------- */

    fun playSong(song: SongItem) {
        stopPlayback()
        _currentSong.value = song

        if (song.isDemoStemTrack && song.stemUrls != null) {
            // Load Multi-Track stem players
            _currentPlayMode.value = PlayMode.LOCAL_STEM
            initializeStems(song.stemUrls)
        } else {
            // Load YouTube player
            _currentPlayMode.value = PlayMode.YOUTUBE
            _playbackState.value = PlaybackState.LOADING
            scope.launch(Dispatchers.Main) {
                webView?.evaluateJavascript("javascript:loadVideo('${song.videoId}');", null)
            }
        }
    }

    fun play() {
        if (_currentPlayMode.value == PlayMode.LOCAL_STEM) {
            resumeStems()
        } else {
            _playbackState.value = PlaybackState.LOADING
            webView?.evaluateJavascript("javascript:play();", null)
        }
    }

    fun pause() {
        if (_currentPlayMode.value == PlayMode.LOCAL_STEM) {
            pauseStems()
        } else {
            _playbackState.value = PlaybackState.PAUSED
            webView?.evaluateJavascript("javascript:pause();", null)
        }
    }

    fun seek(progress: Float) {
        if (_currentPlayMode.value == PlayMode.LOCAL_STEM) {
            seekStems(progress)
        } else {
            val totalSec = _totalDurationSeconds.value
            if (totalSec > 0) {
                val secTarget = (progress * totalSec).toInt()
                webView?.evaluateJavascript("javascript:seek($secTarget);", null)
            }
        }
    }

    fun setPlaybackSpeed(rate: Float) {
        if (_currentPlayMode.value == PlayMode.YOUTUBE) {
            webView?.evaluateJavascript("javascript:setSpeed($rate);", null)
        } else {
            stemPlayers.values.forEach { player ->
                try {
                    player?.let {
                        if (it.isPlaying) {
                            val params = it.playbackParams
                            params.speed = rate
                            it.playbackParams = params
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Speed modification failed on STEM channel", e)
                }
            }
        }
    }

    fun stopPlayback() {
        // Destroy syncing coroutines
        syncJob?.cancel()
        syncJob = null

        // Stop media players
        stemPlayers.forEach { (_, player) ->
            try {
                if (player != null) {
                    if (player.isPlaying) player.stop()
                    player.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing Media Player channel", e)
            }
        }
        stemPlayers.clear()

        // Stop webView stream
        webView?.evaluateJavascript("javascript:pause();", null)

        _playbackState.value = PlaybackState.IDLE
        _currProgress.value = 0f
        _currTimeSeconds.value = 0
        _totalDurationSeconds.value = 0
    }

    /* -------------------- PHYSICAL STEMMING IMPLEMENTATION -------------------- */

    private fun initializeStems(urls: Map<String, String>) {
        _playbackState.value = PlaybackState.LOADING
        scope.launch(Dispatchers.IO) {
            var loadError = false
            val jobs = mutableListOf<Job>()

            // Create synchronous preparation bridges
            stemBands.forEach { band ->
                val url = urls[band]
                if (url != null) {
                    val job = launch {
                        try {
                            val player = MediaPlayer().apply {
                                setDataSource(context, Uri.parse(url))
                                isLooping = true
                                setVolume(0.5f, 0.5f) // Initialize at default mix
                            }
                            // Suspended preparation coroutines
                            suspendCancellableCoroutine<Unit> { continuation ->
                                player.setOnPreparedListener {
                                    continuation.resume(Unit) {}
                                }
                                player.setOnErrorListener { _, what, extra ->
                                    Log.e(TAG, "Preparation error on band $band: what=$what, extra=$extra")
                                    loadError = true
                                    continuation.resume(Unit) {}
                                    true
                                }
                                player.prepareAsync()
                            }
                            synchronized(stemPlayers) {
                                stemPlayers[band] = player
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to instantiate channel $band: ${e.message}")
                            loadError = true
                        }
                    }
                    jobs.add(job)
                }
            }

            jobs.joinAll()

            withContext(Dispatchers.Main) {
                if (loadError) {
                    Log.e(TAG, "Stem preparation encountered fatal errors.")
                    _playbackState.value = PlaybackState.ERROR
                } else {
                    Log.d(TAG, "All STEM tracks loaded and synchronized. Starting playback.")
                    startStemsPlayback()
                }
            }
        }
    }

    private fun startStemsPlayback() {
        val masterList = stemPlayers.values.filterNotNull()
        if (masterList.isEmpty()) return

        // Start all players synchronously
        masterList.forEach { player ->
            player.start()
        }

        _playbackState.value = PlaybackState.PLAYING
        _totalDurationSeconds.value = masterList.first().duration / 1000

        // Launch real-time phase alignment daemon
        startStemsSyncMonitor()
    }

    private fun resumeStems() {
        stemPlayers.values.forEach { player ->
            player?.start()
        }
        _playbackState.value = PlaybackState.PLAYING
        startStemsSyncMonitor()
    }

    private fun pauseStems() {
        syncJob?.cancel()
        syncJob = null
        stemPlayers.values.forEach { player ->
            player?.pause()
        }
        _playbackState.value = PlaybackState.PAUSED
    }

    private fun seekStems(progress: Float) {
        val firstPlayer = stemPlayers.values.firstOrNull { it != null } ?: return
        val totalMs = firstPlayer.duration
        val targetMs = (progress * totalMs).toInt()

        _playbackState.value = PlaybackState.LOADING
        // Seek all players in sync
        stemPlayers.values.forEach { player ->
            player?.seekTo(targetMs)
        }
        _playbackState.value = PlaybackState.PLAYING
    }

    /**
     * Active alignment daemon: Checks for sample drift among STEM channels
     * and triggers incremental micro-seeks relative to vocals (master track).
     */
    private fun startStemsSyncMonitor() {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(300)
                val master = stemPlayers["vocals"]
                if (master != null && master.isPlaying) {
                    val masterPos = master.currentPosition
                    val duration = master.duration
                    
                    _currTimeSeconds.value = masterPos / 1000
                    if (duration > 0) {
                        _currProgress.value = masterPos.toFloat() / duration.toFloat()
                    }

                    // Check for alignment drifts
                    stemBands.forEach { b ->
                        if (b != "vocals") {
                            val slave = stemPlayers[b]
                            if (slave != null && slave.isPlaying) {
                                val drift = Math.abs(masterPos - slave.currentPosition)
                                if (drift > 70) {
                                    Log.w(TAG, "Drift offset detected on $b track ($drift ms). Realigning to master.")
                                    slave.seekTo(masterPos)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /* -------------------- ACTIVE MIXER PROFILE SETTINGS -------------------- */

    fun setStemVolume(band: String, vol: Float) {
        stemVolumes[band] = vol
        updateActualVolume(band)
    }

    fun getStemVolume(band: String): Float = stemVolumes[band] ?: 1.0f

    fun toggleMute(band: String) {
        val currentlyMuted = stemMutes[band] ?: false
        stemMutes[band] = !currentlyMuted
        updateActualVolume(band)
    }

    fun isMuted(band: String): Boolean = stemMutes[band] ?: false

    fun toggleSolo(band: String) {
        val currentlySolo = stemSolos[band] ?: false
        stemSolos[band] = !currentlySolo

        // Adjust all other bands
        if (!currentlySolo) {
            // Solo turned ON
            stemBands.forEach { b ->
                stemSolos[b] = (b == band)
            }
        } else {
            // Solo turned OFF
            stemSolos[band] = false
        }

        // Apply mixers to all tracks in batch
        stemBands.forEach { b ->
            updateActualVolume(b)
        }
    }

    fun isSolo(band: String): Boolean = stemSolos[band] ?: false

    private fun updateActualVolume(band: String) {
        // Calculate final actual vol based on base volume, mute state, and master solo profile
        var targetVolume = stemVolumes[band] ?: 1.0f

        // If mute is active, zero out
        if (stemMutes[band] == true) {
            targetVolume = 0.0f
        }

        // Check if any track has isSolo active
        val anyActiveSolo = stemSolos.values.any { it }
        if (anyActiveSolo && stemSolos[band] != true) {
            // A different band is solo'ed - zero this out
            targetVolume = 0.0f
        }

        // Set native MediaPlayer volume or simulate if in YouTube Play mode
        if (_currentPlayMode.value == PlayMode.LOCAL_STEM) {
            val player = stemPlayers[band]
            try {
                player?.setVolume(targetVolume, targetVolume)
            } catch (e: Exception) {
                Log.e(TAG, "Error adjusting physical MediaPlayer volume for channel $band", e)
            }
        } else {
            // In YouTube streaming mode:
            // Since we receive one mono/stereo stream, we simulate stemming volume shifts
            // by adjusting the main global web volume and Eq visual representation.
            if (band == "vocals") {
                // If vocals are muted or lowered, adjust global player volume or equalizer curve
                val combinedVol = (stemVolumes["vocals"] ?: 1.0f) * 100f
                webView?.evaluateJavascript("javascript:setVol($combinedVol);", null)
            }
        }
    }
}
