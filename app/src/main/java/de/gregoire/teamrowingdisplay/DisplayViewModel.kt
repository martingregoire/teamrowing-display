package de.gregoire.teamrowingdisplay

import android.app.Application
import android.net.Uri
import android.os.SystemClock
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
    val error: String? = null,
)

class DisplayViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TeamrowingApp
    private val auth = app.spotifyAuth
    private val api = app.spotifyApi
    private val bpmRepository = app.bpmRepository

    var state by mutableStateOf(DisplayState(loggedIn = auth.isLoggedIn))
        private set

    val bpmEntries: StateFlow<Map<String, BpmEntry>> = bpmRepository.entries

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

                val now = SystemClock.elapsedRealtime()
                if (track != null && (trackChanged || now - queueFetchedAt >= QUEUE_REFRESH_MS)) {
                    state = state.copy(upcoming = api.getQueue().upcoming)
                    queueFetchedAt = now
                }

                // Tracks the trainer didn't prepare are looked up on the fly, without delaying the polling
                val tracks = listOfNotNull(track) + state.upcoming.take(BPM_LOOKAHEAD)
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
    }
}
