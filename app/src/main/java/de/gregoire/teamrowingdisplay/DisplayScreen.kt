package de.gregoire.teamrowingdisplay

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.bpm.BpmSource
import de.gregoire.teamrowingdisplay.bpm.formatBpm
import de.gregoire.teamrowingdisplay.spotify.PlaybackState
import de.gregoire.teamrowingdisplay.spotify.Track
import kotlinx.coroutines.delay

// High-contrast colors for reading from a distance
private val Background = Color.Black
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFB3B3B3)
private val Accent = Color(0xFFFFC107)
private val ProgressTrack = Color(0xFF333333)
private val ErrorColor = Color(0xFFFF6B6B)

/**
 * Full-screen display for the team: the current track with tempo and progress, and the next track.
 * Tapping the screen shows the controls for a few seconds.
 */
@Composable
fun DisplayScreen(
    playback: PlaybackState?,
    next: Track?,
    bpmEntries: Map<String, BpmEntry>,
    error: String?,
    onPrepareSessions: () -> Unit,
    onLogout: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var controlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(CONTROLS_VISIBLE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .clickable(interactionSource = null, indication = null) { controlsVisible = !controlsVisible }
            .padding(contentPadding)
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        val current = playback?.track
        if (current == null) {
            Text(
                stringResource(R.string.nothing_playing),
                color = SecondaryText,
                fontSize = 64.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                TrackRow(current, bpmEntries[current.uri], large = true, modifier = Modifier.weight(0.6f))
                PlaybackProgress(playback, current, modifier = Modifier.padding(vertical = 24.dp))
                if (next != null) {
                    Text(stringResource(R.string.up_next).uppercase(), color = SecondaryText, fontSize = 24.sp)
                    TrackRow(next, bpmEntries[next.uri], large = false, modifier = Modifier.weight(0.4f))
                } else {
                    Spacer(Modifier.weight(0.4f))
                }
            }
        }

        if (error != null) {
            Text(
                error,
                color = ErrorColor,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.6f),
            )
        }
        if (controlsVisible) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onPrepareSessions) { Text(stringResource(R.string.prepare_sessions)) }
                TextButton(onClick = onLogout) { Text(stringResource(R.string.logout_button), color = SecondaryText) }
            }
        }
    }
}

/** Tempo on the left (left out for "No tempo" tracks), title and artist on the right, scaled to fit. */
@Composable
private fun TrackRow(track: Track, bpmEntry: BpmEntry?, large: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        if (bpmEntry?.hidden != true) {
            Box(
                modifier = Modifier.width(BPM_WIDTH).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = bpmEntry?.bpm?.let(::formatBpm) ?: "–",
                    style = TextStyle(color = if (large) Accent else PrimaryText, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(maxFontSize = if (large) 300.sp else 160.sp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                text = track.title,
                style = TextStyle(color = PrimaryText, fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = if (large) 88.sp else 56.sp),
            )
            val artists = if (large) track.artists else "${track.artists} · ${formatDuration(track.durationMs)}"
            Text(
                artists,
                color = SecondaryText,
                fontSize = if (large) 40.sp else 30.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Progress bar with position and duration; the position advances locally between polls. */
@Composable
private fun PlaybackProgress(playback: PlaybackState, track: Track, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playback) {
        while (playback.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(PROGRESS_TICK_MS)
        }
    }
    val position = playback.positionAt(now)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { if (track.durationMs > 0) position.toFloat() / track.durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(12.dp),
            color = Accent,
            trackColor = ProgressTrack,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(position), color = PrimaryText, fontSize = 32.sp)
            Spacer(Modifier.weight(1f))
            if (!playback.isPlaying) {
                Text(stringResource(R.string.paused).uppercase(), color = Accent, fontSize = 32.sp)
                Spacer(Modifier.weight(1f))
            }
            Text(formatDuration(track.durationMs), color = PrimaryText, fontSize = 32.sp)
        }
    }
}

/** Formats milliseconds as m:ss. */
private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private val BPM_WIDTH = 360.dp
private const val PROGRESS_TICK_MS = 250L
private const val CONTROLS_VISIBLE_MS = 5_000L

@Preview(widthDp = 1280, heightDp = 800)
@Composable
fun DisplayScreenPreview() {
    DisplayScreen(
        playback = PlaybackState(
            track = Track("spotify:track:1", "You Can Leave Your Hat On", "Joe Cocker", 255_000),
            progressMs = 83_000,
            isPlaying = true,
            fetchedAtMs = 0,
        ),
        next = Track("spotify:track:2", "Over My Shoulder", "Mike + The Mechanics", 217_000),
        bpmEntries = mapOf(
            "spotify:track:1" to BpmEntry(173.8, BpmSource.RECCOBEATS, manualBpm = 22.0),
            "spotify:track:2" to BpmEntry(92.4, BpmSource.RECCOBEATS, manualBpm = 23.0),
        ),
        error = null,
        onPrepareSessions = {},
        onLogout = {},
    )
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
fun DisplayScreenNoTempoPreview() {
    DisplayScreen(
        playback = PlaybackState(
            track = Track("spotify:track:3", "Stranded", "Lutricia McNeal", 213_000),
            progressMs = 12_000,
            isPlaying = false,
            fetchedAtMs = 0,
        ),
        next = null,
        bpmEntries = mapOf("spotify:track:3" to BpmEntry(hidden = true)),
        error = null,
        onPrepareSessions = {},
        onLogout = {},
    )
}
