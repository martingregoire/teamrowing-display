package de.gregoire.teamrowingdisplay

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gregoire.teamrowingdisplay.spotify.PlaybackQueue
import de.gregoire.teamrowingdisplay.spotify.SpotifyApi
import de.gregoire.teamrowingdisplay.spotify.SpotifyApiException
import de.gregoire.teamrowingdisplay.spotify.SpotifyAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

data class DisplayState(
    val loggedIn: Boolean,
    val queue: PlaybackQueue? = null,
    val error: String? = null,
)

class DisplayViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = SpotifyAuth(application)
    private val api = SpotifyApi(auth)

    var state by mutableStateOf(DisplayState(loggedIn = auth.isLoggedIn))
        private set

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

    /** Polls the playback queue until the calling coroutine is cancelled, i.e. while the screen is visible. */
    suspend fun pollQueue() {
        while (true) {
            val delayMs = try {
                state = state.copy(queue = api.getQueue(), error = null)
                POLL_INTERVAL_MS
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

    fun logout() {
        auth.logout()
        state = DisplayState(loggedIn = false)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
    }
}
