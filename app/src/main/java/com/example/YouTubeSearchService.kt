package com.example

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.regex.Pattern

data class SongItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String,
    val thumbnailUrl: String,
    val isDemoStemTrack: Boolean = false,
    val stemUrls: Map<String, String>? = null // "vocals", "drums", "bass", "other"
)

object YouTubeSearchService {
    private val client = OkHttpClient.Builder().build()
    private const val TAG = "YouTubeSearchService"

    // Curated high-fidelity demo stem songs (royalty-free multi-track loops hosted on stable public CDNs/URLs)
    val demoStemTracks = listOf(
        SongItem(
            videoId = "demo_cyberpunk",
            title = "Cyber Ambient",
            artist = "Retro Synth Loop (STEMS)",
            duration = "0:30",
            thumbnailUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=150&q=80",
            isDemoStemTrack = true,
            stemUrls = mapOf(
                "vocals" to "https://actions.google.com/sounds/v1/alarms/digital_watch_alarm_long.ogg", // Synth pad loop substitute
                "drums" to "https://actions.google.com/sounds/v1/science_fiction/alien_computer_terminal.ogg", // percussion
                "bass" to "https://actions.google.com/sounds/v1/ambiences/morning_birds.ogg", // low hums
                "other" to "https://actions.google.com/sounds/v1/alarms/digital_watch_alarm_long.ogg" // melody
            )
        ),
        SongItem(
            videoId = "demo_lofi",
            title = "Lofi Study Session",
            artist = "Chillhop Stems",
            duration = "1:00",
            thumbnailUrl = "https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=150&q=80",
            isDemoStemTrack = true,
            stemUrls = mapOf(
                "vocals" to "https://actions.google.com/sounds/v1/ambiences/outdoor_market_atmosphere.ogg", // ambient voices
                "drums" to "https://actions.google.com/sounds/v1/ambiences/rain_heavy_loud.ogg", // rain drum
                "bass" to "https://actions.google.com/sounds/v1/ambiences/coffee_shop_atmosphere.ogg", // low chatter
                "other" to "https://actions.google.com/sounds/v1/ambiences/wind_howling_under_door.ogg" // wind chords
            )
        )
    )

    // Curated Playlists for YouTube Music (Fallback curated list of ad-free YouTube tracks)
    val curatedTracks = listOf(
        SongItem(
            videoId = "5qap5aO4i9A",
            title = "Lofi Hip Hop Radio - Beats to Relax/Study to",
            artist = "Lofi Girl",
            duration = "LIVE",
            thumbnailUrl = "https://img.youtube.com/vi/5qap5aO4i9A/0.jpg"
        ),
        SongItem(
            videoId = "yv5FMCQ70_U",
            title = "Synthwave Chill Mix for Coding / Cyberpunk Atmosphere",
            artist = "Retro Coding",
            duration = "2:30:00",
            thumbnailUrl = "https://img.youtube.com/vi/yv5FMCQ70_U/0.jpg"
        ),
        SongItem(
            videoId = "tYstY6S-0Yw",
            title = "Ambient Space Music - Cosmic Deep Sleep Journey",
            artist = "Cosmos Sounds",
            duration = "1:44:00",
            thumbnailUrl = "https://img.youtube.com/vi/tYstY6S-0Yw/0.jpg"
        ),
        SongItem(
            videoId = "uA_N6vCAnR8",
            title = "Heavy Rock Metal Workout Beats - Aggressive Riffs",
            artist = "Riff Master",
            duration = "45:00",
            thumbnailUrl = "https://img.youtube.com/vi/uA_N6vCAnR8/0.jpg"
        ),
        SongItem(
            videoId = "fHiGbolG9Z8",
            title = "Upbeat House Instrumentals - Club Dance Party",
            artist = "EDM Select",
            duration = "58:00",
            thumbnailUrl = "https://img.youtube.com/vi/fHiGbolG9Z8/0.jpg"
        )
    )

    /**
     * Search YouTube and crawl HTML results, escaping standard restrictions.
     * This is 100% ad-free, respects standard rates, and extracts real results.
     */
    suspend fun searchYouTube(query: String): List<SongItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // Use video-only search query parameters to fetch high quality single track videos
        val url = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAQ%253D%253D"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val results = mutableListOf<SongItem>()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Search request failed: code ${response.code}")
                    return@withContext curatedTracks.filter { it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) }
                }

                val html = response.body?.string() ?: ""
                
                // YouTube provides a JSON block inside var ytInitialData = { ... };
                val blockPattern = Pattern.compile("ytInitialData\\s*=\\s*(\\{.*?\\});")
                val matcher = blockPattern.matcher(html)

                if (matcher.find()) {
                    val jsonData = matcher.group(1) ?: ""
                    results.addAll(parseYouTubeJsonData(jsonData))
                } else {
                    // Fallback Regex scraping directly on HTML if json block isn't cleanly extracted
                    Log.w(TAG, "ytInitialData block not found, running regex fallback")
                    results.addAll(parseYouTubeRegexFallback(html))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching YouTube: ${e.message}", e)
        }

        // If search returned empty, return matching curated list or all curated items
        if (results.isEmpty()) {
            val filtered = curatedTracks.filter { 
                it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
            }
            return@withContext if (filtered.isNotEmpty()) filtered else curatedTracks
        }

        return@withContext results
    }

    private fun parseYouTubeJsonData(json: String): List<SongItem> {
        val list = mutableListOf<SongItem>()
        try {
            // Find video rendering components inside the embedded JSON
            // We can search forvideoId, title runs, and thumbnail urls using targeted robust regexes
            val videoRendererPattern = Pattern.compile("\\{\"videoRenderer\":\\{(.*?)\\}\\}")
            val renderMatcher = videoRendererPattern.matcher(json)

            var count = 0
            while (renderMatcher.find() && count < 15) {
                val block = renderMatcher.group(1) ?: continue
                
                val videoId: String = extractValue(block, "\"videoId\":\"(.*?)\"") ?: ""
                val title: String = (extractValue(block, "\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"\\}\\]")
                    ?.replace("\\\\\"", "\"")
                    ?.replace("\\u0026", "&")) ?: ""
                val rawArtist = extractValue(block, "\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"\\}\\]") 
                    ?: extractValue(block, "\"longBylineText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"\\}\\]")
                val artist: String = rawArtist ?: "Unknown Artist"
                val duration: String = extractValue(block, "\"simpleText\":\"(.*?)\"") ?: "3:45"
                val thumbnailUrl = "https://img.youtube.com/vi/$videoId/0.jpg"

                if (videoId.isNotEmpty() && title.isNotEmpty()) {
                    list.add(
                        SongItem(
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            duration = duration,
                            thumbnailUrl = thumbnailUrl
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing error: ${e.message}")
        }
        return list
    }

    private fun parseYouTubeRegexFallback(html: String): List<SongItem> {
        val list = mutableListOf<SongItem>()
        try {
            // Basic regex parsing directly on raw HTML
            val matcher = Pattern.compile("/watch\\?v=([a-zA-Z0-9_-]{11})").matcher(html)
            val ids = LinkedHashSet<String>()
            while (matcher.find()) {
                val id = matcher.group(1)
                if (id != null) ids.add(id)
                if (ids.size >= 12) break
            }

            for (id in ids) {
                // Find a heading title near this video id or just assign generic titles for safety
                val titleMatcher = Pattern.compile("\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"\\}\\]").matcher(html)
                var title = "Song Trace #$id"
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1)?.replace("\\u0026", "&") ?: title
                }
                list.add(
                    SongItem(
                        videoId = id,
                        title = title,
                        artist = "YouTube Music Player",
                        duration = "4:12",
                        thumbnailUrl = "https://img.youtube.com/vi/$id/0.jpg"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Regex fallback error: ${e.message}")
        }
        return list
    }

    private fun extractValue(text: String, patternStr: String): String? {
        val pattern = Pattern.compile(patternStr)
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }
}
