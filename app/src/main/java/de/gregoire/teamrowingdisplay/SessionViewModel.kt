package de.gregoire.teamrowingdisplay

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.spotify.Playlist
import de.gregoire.teamrowingdisplay.spotify.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

/** Session preparation: one Spotify playlist is one training session, whose BPM values the trainer checks. */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TeamrowingApp
    private val api = app.spotifyApi
    private val bpmRepository = app.bpmRepository
    private var playlistJob: Job? = null

    var playlists by mutableStateOf<List<Playlist>?>(null)
        private set
    var selectedPlaylist by mutableStateOf<Playlist?>(null)
        private set
    var tracks by mutableStateOf<List<Track>?>(null)
        private set
    var lookingUpBpm by mutableStateOf(false)
        private set
    var lookupIncomplete by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val bpmEntries: StateFlow<Map<String, BpmEntry>> = bpmRepository.entries

    fun loadPlaylists() {
        viewModelScope.launch {
            error = null
            try {
                playlists = api.getPlaylists()
            } catch (e: IOException) {
                error = e.message
            } catch (e: JSONException) {
                error = e.message
            }
        }
    }

    /** Loads the playlist's tracks and looks up the BPM of tracks not seen before. */
    fun openPlaylist(playlist: Playlist) {
        selectedPlaylist = playlist
        tracks = null
        error = null
        lookupIncomplete = false
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            try {
                val playlistTracks = api.getPlaylistTracks(playlist.id)
                tracks = playlistTracks
                lookingUpBpm = true
                lookupIncomplete = !bpmRepository.lookUpMissing(playlistTracks)
            } catch (e: IOException) {
                error = e.message
            } catch (e: JSONException) {
                error = e.message
            } finally {
                lookingUpBpm = false
            }
        }
    }

    fun closePlaylist() {
        playlistJob?.cancel()
        selectedPlaylist = null
        tracks = null
    }

    fun multiplyBpm(track: Track, factor: Double) {
        viewModelScope.launch { bpmRepository.multiplyBpm(track.uri, factor) }
    }

    fun addBpm(track: Track, delta: Double) {
        viewModelScope.launch { bpmRepository.addBpm(track.uri, delta) }
    }

    fun setTempoHidden(track: Track, hidden: Boolean) {
        viewModelScope.launch { bpmRepository.setHidden(track.uri, hidden) }
    }

    fun setManualBpm(track: Track, bpm: Double?) {
        viewModelScope.launch { bpmRepository.setManualBpm(track.uri, bpm) }
    }
}
