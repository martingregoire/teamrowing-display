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
import de.gregoire.teamrowingdisplay.sync.PlaybackUpdate
import de.gregoire.teamrowingdisplay.sync.QueueUpdate
import de.gregoire.teamrowingdisplay.sync.SyncStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    init {
        // Shows the remote app's playback changes instantly, and wakes the polling to fetch the queue
        viewModelScope.launch {
            app.syncServer.remotePlayback.filterNotNull().collect { update ->
                showPlayback(update.toPlaybackState())
                app.syncServer.remoteQueue.value?.let(::showQueue)
                wakeUp.trySend(Unit)
            }
        }
        // The remote app also sends Spotify's queue, so the next track is shown without internet access
        viewModelScope.launch {
            app.syncServer.remoteQueue.filterNotNull().collect(::showQueue)
        }
    }

    val bpmEntries: StateFlow<Map<String, BpmEntry>> = bpmRepository.entries

    val displaySettings: StateFlow<DisplaySettings> = app.displaySettingsStore.settings

    val syncStatus: StateFlow<SyncStatus> = app.syncServer.status

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
     * Polls until the calling coroutine is cancelled, i.e. while the screen is visible. While the remote
     * app is connected, it reports playback changes instantly over Bluetooth, and only the queue is
     * fetched; otherwise the playback state is polled too. The queue is fetched when the track changes,
     * and every [QUEUE_REFRESH_MS] to catch manual changes. If the remote app sends the queue too,
     * nothing is fetched from Spotify, so the display works without internet access.
     */
    suspend fun pollPlayback() {
        var queueFetchedAt = 0L
        var queueTrackUri: String? = null
        var staleQueueAttempts = 0
        while (true) {
            val delayMs = try {
                val remoteConnected = app.syncServer.remotePlayback.value != null
                val remoteSendsQueue = app.syncServer.remoteQueue.value != null
                val playback = if (remoteConnected) state.playback else api.getPlaybackState().also(::showPlayback)
                val track = playback?.track

                // The remote app's queue is without autoplay tracks already
                if (!remoteSendsQueue) {
                    updateSessionPlaylist(playback?.contextUri)
                    state = state.copy(sessionTrackUris = sessionPlaylist?.trackUris)
                }

                val now = SystemClock.elapsedRealtime()
                var queueStale = false
                if (
                    !remoteSendsQueue && track != null &&
                    (track.uri != queueTrackUri || now - queueFetchedAt >= QUEUE_REFRESH_MS)
                ) {
                    val queue = api.getQueue()
                    // Right after a track change, Spotify may still report the queue of the previous track
                    if (queue.currentlyPlaying?.uri == track.uri || ++staleQueueAttempts > MAX_STALE_QUEUE_ATTEMPTS) {
                        state = state.copy(upcoming = queue.upcoming)
                        queueTrackUri = track.uri
                        queueFetchedAt = now
                        staleQueueAttempts = 0
                    } else {
                        queueStale = true
                    }
                }

                // Tracks the trainer didn't prepare are looked up on the fly, without delaying the polling
                val tracks = listOfNotNull(track) + state.upcoming.filter(state::isInSession).take(BPM_LOOKAHEAD)
                viewModelScope.launch { bpmRepository.lookUpMissing(tracks) }

                // E.g. a network error from before the remote app connected and took over
                if (state.error != null) state = state.copy(error = null)

                when {
                    queueStale -> STALE_QUEUE_RETRY_MS
                    remoteConnected -> POLL_INTERVAL_MS
                    else -> nextPollDelay(playback)
                }
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
            // A playback update from the remote app ends the wait early
            withTimeoutOrNull(delayMs) { wakeUp.receive() }
        }
    }

    /** Shows a new playback state right away, advancing the known queue if the new track was next. */
    private fun showPlayback(playback: PlaybackState?) {
        val track = playback?.track
        val trackChanged = track?.uri != state.playback?.track?.uri
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
    }

    /** Shows the remote app's queue, once the track it follows is shown. */
    private fun showQueue(queue: QueueUpdate) {
        if (queue.trackUri != state.playback?.track?.uri) return
        state = state.copy(upcoming = queue.upcoming, sessionTrackUris = null)
    }

    /** The remote app's playback state; the track's ISRC, used for BPM lookups, comes from the queue. */
    private fun PlaybackUpdate.toPlaybackState(): PlaybackState {
        val track = trackUri?.let { uri ->
            val queued = state.upcoming.firstOrNull { it.uri == uri } ?: state.playback?.track?.takeIf { it.uri == uri }
            Track(uri = uri, title = title, artists = artists, durationMs = durationMs, isrc = queued?.isrc)
        }
        return PlaybackState(
            track = track,
            progressMs = positionMs,
            isPlaying = !isPaused,
            contextUri = contextUri,
            fetchedAtMs = SystemClock.elapsedRealtime(),
        )
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
        const val STALE_QUEUE_RETRY_MS = 500L
        const val MAX_STALE_QUEUE_ATTEMPTS = 6
        const val BPM_LOOKAHEAD = 5
        const val TAG = "DisplayViewModel"
        const val PLAYLIST_URI_PREFIX = "spotify:playlist:"
        val OTHER_CONTEXT_PREFIXES = listOf("spotify:album:", "spotify:artist:", "spotify:show:")

        /** Picks up changes to the playlist made while it is playing. */
        const val PLAYLIST_REFRESH_MS = 5 * 60_000L
    }
}
