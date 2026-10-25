package de.gregoire.teamrowingdisplay

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.spotify.PlaybackState
import de.gregoire.teamrowingdisplay.spotify.SpotifyApiException
import de.gregoire.teamrowingdisplay.spotify.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

data class DisplayState(
    val loggedIn: Boolean,
    val playback: PlaybackState? = null,
    val upcoming: List<Track> = emptyList(),
    /** Tracks of the session playlist, or null if not playing one of the user's playlists. */
    val sessionTrackUris: Set<String>? = null,
    val error: String? = null,
) {
    /** The next track, unless the playlist ends and Spotify continues with autoplay tracks. */
    val next: Track?
        get() = upcoming.firstOrNull()?.takeIf { isInSession(it) }

    fun isInSession(track: Track): Boolean = sessionTrackUris == null || track.uri in sessionTrackUris
}

/** The playlist being played as a training session, with the URIs of its tracks. */
private class SessionPlaylist(val uri: String, val trackUris: Set<String>?, val fetchedAt: Long)

class DisplayViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TeamrowingApp
    private val auth = app.spotifyAuth
    private val api = app.spotifyApi
    private val bpmRepository = app.bpmRepository

    var state by mutableStateOf(DisplayState(loggedIn = auth.isLoggedIn))
        private set

    private var sessionPlaylist: SessionPlaylist? = null
    private var lastContextUri: String? = null

    val bpmEntries: StateFlow<Map<String, BpmEntry>> = bpmRepository.entries

    val displaySettings: StateFlow<DisplaySettings> = app.displaySettingsStore.settings

    fun updateDisplaySettings(transform: (DisplaySettings) -> DisplaySettings) {
        app.displaySettingsStore.update(transform)
    }

    fun createAuthorizeUri(): Uri = auth.createAuthorizeUri()

    fun handleRedirect(uri: Uri) {
        viewModelScope.launch {
            state = try {
                auth.handleRedirect(uri)
                DisplayState(loggedIn = true)
            } catch (e: IOException) {
                state.copy(error = e.message)
            }
        }
    }

    /**
     * Polls the playback state until the calling coroutine is cancelled, i.e. while the screen is visible.
     * The queue is only fetched when the track changes, and every [QUEUE_REFRESH_MS] to catch manual changes.
     */
    suspend fun pollPlayback() {
        var queueFetchedAt = 0L
        while (true) {
            val delayMs = try {
                val playback = api.getPlaybackState()
                val track = playback?.track
                val trackChanged = track?.uri != state.playback?.track?.uri

                // Show the new track right away, advancing the known queue if it was next
                val upcoming = state.upcoming
                state = state.copy(
                    playback = playback,
                    upcoming = when {
                        track == null -> emptyList()
                        trackChanged && upcoming.firstOrNull()?.uri == track.uri -> upcoming.drop(1)
                        else -> upcoming
                    },
                    error = null,
                )

                updateSessionPlaylist(playback?.contextUri)
                state = state.copy(sessionTrackUris = sessionPlaylist?.trackUris)

                val now = SystemClock.elapsedRealtime()
                if (track != null && (trackChanged || now - queueFetchedAt >= QUEUE_REFRESH_MS)) {
                    // Fetch first: state.copy(upcoming = api.getQueue().upcoming) would copy the state from
                    // before the request and undo changes made meanwhile, such as a logout
                    val upcoming = api.getQueue().upcoming
                    state = state.copy(upcoming = upcoming)
                    queueFetchedAt = now
                }

                // Tracks the trainer didn't prepare are looked up on the fly, without delaying the polling
                val tracks = listOfNotNull(track) + state.upcoming.filter(state::isInSession).take(BPM_LOOKAHEAD)
                viewModelScope.launch { bpmRepository.lookUpMissing(tracks) }

                nextPollDelay(playback)
            } catch (e: SpotifyApiException) {
                state = state.copy(error = e.message)
                e.retryAfterSeconds?.times(1000) ?: POLL_INTERVAL_MS
            } catch (e: IOException) {
                // Network errors, or a failed token refresh that may have logged the user out
                state = state.copy(loggedIn = auth.isLoggedIn, error = e.message)
                POLL_INTERVAL_MS
            } catch (e: JSONException) {
                state = state.copy(error = e.message)
                POLL_INTERVAL_MS
            }
            delay(delayMs)
        }
    }

    /**
     * Keeps the track list of the playlist being played, to tell its tracks apart from Spotify's autoplay
     * tracks after its end. Autoplay may report no or a different context, so only playing another playlist,
     * album, artist, show or Liked Songs replaces the session playlist.
     */
    private suspend fun updateSessionPlaylist(contextUri: String?) {
        if (contextUri != lastContextUri) Log.d(TAG, "Playback context changed to $contextUri")
        lastContextUri = contextUri
        if (contextUri == null) return
        if (!contextUri.startsWith(PLAYLIST_URI_PREFIX)) {
            if (OTHER_CONTEXT_PREFIXES.any { contextUri.startsWith(it) } || contextUri.endsWith(":collection")) {
                sessionPlaylist = null
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        val current = sessionPlaylist
        if (current?.uri == contextUri && now - current.fetchedAt < PLAYLIST_REFRESH_MS) return
        val trackUris = try {
            api.getPlaylistTracks(contextUri.removePrefix(PLAYLIST_URI_PREFIX)).map { it.uri }.toSet()
        } catch (e: SpotifyApiException) {
            // Spotify only returns the tracks of the user's own playlists; show the queue unfiltered then
            Log.i(TAG, "Can't read tracks of $contextUri", e)
            null
        }
        sessionPlaylist = SessionPlaylist(contextUri, trackUris, now)
    }

    /** Polls right after the current track ends, so the display switches to the next track without delay. */
    private fun nextPollDelay(playback: PlaybackState?): Long {
        val track = playback?.track
        if (track == null || !playback.isPlaying) return POLL_INTERVAL_MS
        val remainingMs = track.durationMs - playback.positionAt(SystemClock.elapsedRealtime())
        return (remainingMs + TRACK_END_MARGIN_MS).coerceIn(MIN_POLL_INTERVAL_MS, POLL_INTERVAL_MS)
    }

    fun logout() {
        auth.logout()
        state = DisplayState(loggedIn = false)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MIN_POLL_INTERVAL_MS = 250L
        const val TRACK_END_MARGIN_MS = 200L
        const val QUEUE_REFRESH_MS = 15_000L
        const val BPM_LOOKAHEAD = 5
        const val TAG = "DisplayViewModel"
        const val PLAYLIST_URI_PREFIX = "spotify:playlist:"
        val OTHER_CONTEXT_PREFIXES = listOf("spotify:album:", "spotify:artist:", "spotify:show:")

        /** Picks up changes to the playlist made while it is playing. */
        const val PLAYLIST_REFRESH_MS = 5 * 60_000L
    }
}
