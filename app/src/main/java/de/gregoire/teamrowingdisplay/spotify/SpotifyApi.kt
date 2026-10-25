package de.gregoire.teamrowingdisplay.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Track(
    val uri: String,
    val title: String,
    val artists: String,
    val durationMs: Long,
)

data class PlaybackQueue(
    val currentlyPlaying: Track?,
    val upcoming: List<Track>,
)

class SpotifyApiException(
    val status: Int,
    message: String,
    /** Seconds to wait before the next request, sent by Spotify when rate limiting (HTTP 429). */
    val retryAfterSeconds: Long? = null,
) : IOException(message)

/** Read-only access to the Spotify Web API for the logged-in user. */
class SpotifyApi(private val auth: SpotifyAuth) {

    /** The current track and the upcoming tracks, on whichever device is playing. */
    suspend fun getQueue(): PlaybackQueue {
        val json = get("/me/player/queue") ?: return PlaybackQueue(null, emptyList())
        return PlaybackQueue(
            currentlyPlaying = json.optJSONObject("currently_playing")?.toTrack(),
            upcoming = json.optJSONArray("queue")?.toTracks().orEmpty(),
        )
    }

    /** GET request returning the JSON body, or null for 204 No Content. Retries once with a fresh token on 401. */
    private suspend fun get(path: String): JSONObject? {
        var response = request(path, auth.accessToken())
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            response = request(path, auth.accessToken(forceRefresh = true))
        }
        return when (response.status) {
            HttpURLConnection.HTTP_OK -> JSONObject(response.body)
            HttpURLConnection.HTTP_NO_CONTENT -> null
            else -> throw SpotifyApiException(
                response.status,
                "Spotify API $path failed (${response.status}): ${response.body}",
                response.retryAfterSeconds,
            )
        }
    }

    private suspend fun request(path: String, accessToken: String): Response = withContext(Dispatchers.IO) {
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            Response(
                status = status,
                body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
                retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private class Response(val status: Int, val body: String, val retryAfterSeconds: Long?)

    private companion object {
        const val BASE_URL = "https://api.spotify.com/v1"
    }
}

private fun JSONArray.toTracks(): List<Track> =
    (0 until length()).mapNotNull { optJSONObject(it)?.toTrack() }

/** Maps a track object, or a podcast episode object (with the show name in place of the artists). */
private fun JSONObject.toTrack(): Track {
    val artists = optJSONArray("artists")
        ?.let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("name") } }
        ?.joinToString(", ")
        ?: optJSONObject("show")?.optString("name").orEmpty()
    return Track(
        uri = getString("uri"),
        title = getString("name"),
        artists = artists,
        durationMs = optLong("duration_ms"),
    )
}
