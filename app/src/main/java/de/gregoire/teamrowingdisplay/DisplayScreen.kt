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
import de.gregoire.teamrowingdisplay.sync.SyncStatus
import kotlinx.coroutines.delay

/**
 * Full-screen display for the team: the current track with tempo and progress, and the next track.
 * Tapping the screen shows the controls for a few seconds.
 */
@Composable
fun DisplayScreen(
    playback: PlaybackState?,
    next: Track?,
    bpmEntries: Map<String, BpmEntry>,
    settings: DisplaySettings,
    syncStatus: SyncStatus,
    error: String?,
    onPrepareSessions: () -> Unit,
    onOpenSettings: () -> Unit,
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
    val colors = settings.colors()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .clickable(interactionSource = null, indication = null) { controlsVisible = !controlsVisible }
            .padding(contentPadding)
    ) {
        DisplayContent(playback, next, bpmEntries, settings)

        Box(modifier = Modifier.matchParentSize().padding(DISPLAY_PADDING)) {
            if (error != null) {
                Text(
                    error,
                    color = colors.error,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f),
                )
            }
            if (controlsVisible) {
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(syncStatus.label),
                        color = colors.secondary,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    Button(onClick = onPrepareSessions) { Text(stringResource(R.string.prepare_sessions)) }
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.display_settings)) }
                    TextButton(onClick = onLogout) {
                        Text(stringResource(R.string.logout_button), color = colors.secondary)
                    }
                }
            }
        }
    }
}

private val SyncStatus.label: Int
    get() = when (this) {
        SyncStatus.NO_PERMISSION -> R.string.sync_no_permission
        SyncStatus.BLUETOOTH_OFF -> R.string.sync_bluetooth_off
        SyncStatus.WAITING -> R.string.sync_waiting
        SyncStatus.CONNECTED -> R.string.sync_connected
    }

/** The display itself, without controls; also used as the live preview in the settings. */
@Composable
fun DisplayContent(
    playback: PlaybackState?,
    next: Track?,
    bpmEntries: Map<String, BpmEntry>,
    settings: DisplaySettings,
    modifier: Modifier = Modifier,
) {
    val colors = settings.colors()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(DISPLAY_PADDING)
    ) {
        val current = playback?.track
        if (current == null) {
            Text(
                stringResource(R.string.nothing_playing),
                color = colors.secondary,
                fontSize = 64.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TrackRow(
                track = current,
                bpmEntry = bpmEntries[current.uri],
                settings = settings,
                colors = colors,
                large = true,
                modifier = Modifier.weight(settings.split.currentWeight),
            )
            PlaybackProgress(playback, current, settings, colors, modifier = Modifier.padding(vertical = 24.dp))
            if (settings.showNext && next != null) {
                Text(stringResource(R.string.up_next).uppercase(), color = colors.secondary, fontSize = 24.sp)
                TrackRow(
                    track = next,
                    bpmEntry = bpmEntries[next.uri],
                    settings = settings,
                    colors = colors,
                    large = false,
                    modifier = Modifier.weight(1 - settings.split.currentWeight),
                )
            } else if (settings.showNext) {
                // Keep the current track's size when the next track is temporarily unknown
                Spacer(Modifier.weight(1 - settings.split.currentWeight))
            }
        }
    }
}

/** Tempo on the left (left out for "No tempo" tracks), title and artist on the right, scaled to fit. */
@Composable
private fun TrackRow(
    track: Track,
    bpmEntry: BpmEntry?,
    settings: DisplaySettings,
    colors: DisplayColors,
    large: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        if (bpmEntry?.hidden != true) {
            Column(
                modifier = Modifier.width(settings.tempoSize.width).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (settings.showTempoLabel && settings.tempoLabel.isNotBlank()) {
                    Text(
                        settings.tempoLabel.uppercase(),
                        color = colors.secondary,
                        fontSize = if (large) 28.sp else 20.sp,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier.fillMaxHeight(settings.tempoSize.heightFraction),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = bpmEntry?.bpm?.let(::formatBpm) ?: "–",
                        style = TextStyle(color = if (large) colors.accent else colors.primary, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(maxFontSize = if (large) 400.sp else 220.sp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                text = track.title,
                style = TextStyle(color = colors.primary, fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = if (large) 88.sp else 56.sp),
            )
            val details = buildList {
                if (settings.showArtist) add(track.artists)
                if (!large && settings.showNextDuration) add(formatDuration(track.durationMs))
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    color = colors.secondary,
                    fontSize = if (large) 40.sp else 30.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Progress bar with position and duration; the position advances locally between polls. */
@Composable
private fun PlaybackProgress(
    playback: PlaybackState,
    track: Track,
    settings: DisplaySettings,
    colors: DisplayColors,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playback) {
        while (playback.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(PROGRESS_TICK_MS)
        }
    }
    val position = playback.positionAt(now)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (settings.showProgressBar) {
            LinearProgressIndicator(
                progress = { if (track.durationMs > 0) position.toFloat() / track.durationMs else 0f },
                modifier = Modifier.fillMaxWidth().height(12.dp),
                color = colors.accent,
                trackColor = colors.progressTrack,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        // The pause indicator is shown even when the times are hidden
        if (settings.showTimes || !playback.isPlaying) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (settings.showTimes) Text(formatDuration(position), color = colors.primary, fontSize = 32.sp)
                Spacer(Modifier.weight(1f))
                if (!playback.isPlaying) {
                    Text(stringResource(R.string.paused).uppercase(), color = colors.accent, fontSize = 32.sp)
                    Spacer(Modifier.weight(1f))
                }
                if (settings.showTimes) Text(formatDuration(track.durationMs), color = colors.primary, fontSize = 32.sp)
            }
        }
    }
}

/** Formats milliseconds as m:ss. */
private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private val DISPLAY_PADDING = PaddingValues(horizontal = 48.dp, vertical = 32.dp)
private const val PROGRESS_TICK_MS = 250L
private const val CONTROLS_VISIBLE_MS = 5_000L

/** Sample data for previews, and for the settings preview when nothing is playing. */
object SampleDisplayData {
    val playback = PlaybackState(
        track = Track("spotify:track:sample1", "You Can Leave Your Hat On", "Joe Cocker", 255_000),
        progressMs = 83_000,
        isPlaying = true,
        fetchedAtMs = 0,
    )
    val next = Track("spotify:track:sample2", "Over My Shoulder", "Mike + The Mechanics", 217_000)
    val bpmEntries = mapOf(
        "spotify:track:sample1" to BpmEntry(173.8, BpmSource.RECCOBEATS, manualBpm = 22.0),
        "spotify:track:sample2" to BpmEntry(92.4, BpmSource.RECCOBEATS, manualBpm = 23.0),
    )
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
fun DisplayContentPreview() {
    DisplayContent(
        playback = SampleDisplayData.playback,
        next = SampleDisplayData.next,
        bpmEntries = SampleDisplayData.bpmEntries,
        settings = DisplaySettings(),
    )
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
fun DisplayContentLightPreview() {
    DisplayContent(
        playback = SampleDisplayData.playback.copy(isPlaying = false),
        next = null,
        bpmEntries = mapOf("spotify:track:sample1" to BpmEntry(hidden = true)),
        settings = DisplaySettings(
            showNext = false,
            colorScheme = DisplayColorScheme.LIGHT,
            accent = AccentColor.BLUE,
        ),
    )
}
