package de.gregoire.teamrowingdisplay

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.session.SessionTimeline
import de.gregoire.teamrowingdisplay.session.graphTempoRange
import de.gregoire.teamrowingdisplay.spotify.PlaybackState
import de.gregoire.teamrowingdisplay.spotify.Track
import kotlinx.coroutines.delay

/**
 * The display, with the current track's title and artist alternating with the session graph if that's
 * turned on and there is a [timeline]: the title for [DisplaySettings.mainSeconds], the graph for
 * [DisplaySettings.graphSeconds]. Each track starts with its title, which is also kept for the last
 * seconds of a track, so a track change isn't missed. A new [restartKey], e.g. on tapping, switches back
 * and starts over. [holdAtTrackEnd] is off for the settings preview, whose sample track doesn't advance.
 */
@Composable
fun AlternatingDisplay(
    playback: PlaybackState?,
    next: Track?,
    bpmEntries: Map<String, BpmEntry>,
    timeline: SessionTimeline?,
    settings: DisplaySettings,
    restartKey: Int,
    modifier: Modifier = Modifier,
    holdAtTrackEnd: Boolean = true,
) {
    val graphAvailable = settings.alternateGraph && timeline != null
    val currentPlayback by rememberUpdatedState(playback)
    fun remainingMs(): Long {
        if (!holdAtTrackEnd) return Long.MAX_VALUE
        val state = currentPlayback ?: return 0
        val track = state.track ?: return 0
        return track.durationMs - state.positionAt(SystemClock.elapsedRealtime())
    }

    var showGraph by remember { mutableStateOf(false) }
    LaunchedEffect(graphAvailable, playback?.track?.uri, restartKey, settings.mainSeconds, settings.graphSeconds) {
        showGraph = false
        if (!graphAvailable) return@LaunchedEffect
        val graphMs = settings.graphSeconds * 1000L
        while (true) {
            delay(settings.mainSeconds * 1000L)
            // The graph would reach the track's last seconds, so the title stays until the next track,
            // checked again after another round in case of a seek back
            if (remainingMs() < graphMs + TRACK_END_HOLD_MS) continue
            showGraph = true
            // A seek forward may reach the track's last seconds earlier
            val graphEnd = SystemClock.elapsedRealtime() + graphMs
            while (SystemClock.elapsedRealtime() < graphEnd && remainingMs() >= TRACK_END_HOLD_MS) {
                delay(PROGRESS_TICK_MS)
            }
            showGraph = false
        }
    }

    DisplayContent(
        playback = playback,
        next = next,
        bpmEntries = bpmEntries,
        settings = settings,
        modifier = modifier,
        graph = if (timeline != null && playback != null) {
            { graphModifier -> SessionGraph(timeline, playback, settings.colors(), graphModifier) }
        } else {
            null
        },
        showGraph = showGraph,
    )
}

/**
 * The session graph: the rowing tracks as bars over the session's rowing time, as high as their tempo,
 * with a line where the session stands. Tracks without a rowing tempo yet keep their time without a bar.
 */
@Composable
fun SessionGraph(timeline: SessionTimeline, playback: PlaybackState, colors: DisplayColors, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playback) {
        while (playback.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(PROGRESS_TICK_MS)
        }
    }
    val cursorMs = timeline.cursorMs(playback.positionAt(now))
    val tempos = remember(timeline.tempoRange) { graphTempoRange(timeline.tempoRange) }

    Canvas(modifier) {
        val totalMs = timeline.totalMs.coerceAtLeast(1)
        fun x(ms: Long) = size.width * ms / totalMs
        fun y(tempo: Double) =
            size.height * (1 - ((tempo - tempos.start) / (tempos.endInclusive - tempos.start)).toFloat().coerceIn(0f, 1f))

        val gap = 2.dp.toPx()
        for (track in timeline.tracks) {
            val tempo = track.tempo ?: continue
            val left = x(track.startMs) + gap
            val top = y(tempo)
            drawRect(colors.accent, Offset(left, top), Size((x(track.endMs) - gap - left).coerceAtLeast(1f), size.height - top))
        }

        val cursorWidth = 4.dp.toPx()
        val cursorX = x(cursorMs).coerceIn(cursorWidth / 2, size.width - cursorWidth / 2)
        drawLine(colors.primary, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = cursorWidth)
    }
}

private const val TRACK_END_HOLD_MS = 10_000L

@Preview(widthDp = 1280, heightDp = 800)
@Composable
fun SessionGraphPreview() {
    DisplayContent(
        playback = SampleDisplayData.playback.copy(isPlaying = false),
        next = SampleDisplayData.next,
        bpmEntries = SampleDisplayData.bpmEntries,
        settings = DisplaySettings(),
        graph = { modifier ->
            SessionGraph(
                SampleDisplayData.timeline,
                SampleDisplayData.playback.copy(isPlaying = false),
                DisplaySettings().colors(),
                modifier,
            )
        },
        showGraph = true,
    )
}
