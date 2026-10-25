package de.gregoire.teamrowingdisplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.bpm.BpmSource
import de.gregoire.teamrowingdisplay.bpm.formatBpm
import de.gregoire.teamrowingdisplay.spotify.Playlist
import de.gregoire.teamrowingdisplay.spotify.Track
import de.gregoire.teamrowingdisplay.ui.theme.TeamrowingDisplayTheme

/** Session preparation: pick a playlist, then check and correct the BPM of its tracks. */
@Composable
fun SessionsScreen(viewModel: SessionViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val playlist = viewModel.selectedPlaylist
    if (playlist == null) {
        BackHandler(onBack = onClose)
        LaunchedEffect(Unit) { viewModel.loadPlaylists() }
        PlaylistsScreen(
            playlists = viewModel.playlists,
            error = viewModel.error,
            onSelect = viewModel::openPlaylist,
            onBack = onClose,
            modifier = modifier,
        )
    } else {
        BackHandler(onBack = viewModel::closePlaylist)
        val bpmEntries by viewModel.bpmEntries.collectAsStateWithLifecycle()
        SessionTracksScreen(
            playlist = playlist,
            tracks = viewModel.tracks,
            bpmEntries = bpmEntries,
            lookingUpBpm = viewModel.lookingUpBpm,
            lookupIncomplete = viewModel.lookupIncomplete,
            error = viewModel.error,
            onMultiplyBpm = viewModel::multiplyBpm,
            onAddBpm = viewModel::addBpm,
            onSetBpm = viewModel::setManualBpm,
            onSetTempoHidden = viewModel::setTempoHidden,
            onBack = viewModel::closePlaylist,
            modifier = modifier,
        )
    }
}

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>?,
    error: String?,
    onSelect: (Playlist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(32.dp)) {
        ScreenHeader(title = stringResource(R.string.sessions_title), onBack = onBack)
        Text(stringResource(R.string.sessions_hint), style = MaterialTheme.typography.bodyMedium)
        error?.let { ErrorText(it) }
        when {
            playlists == null -> if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
            playlists.isEmpty() -> Text(stringResource(R.string.no_playlists), modifier = Modifier.padding(top = 16.dp))
            else -> LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(playlist) }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun SessionTracksScreen(
    playlist: Playlist,
    tracks: List<Track>?,
    bpmEntries: Map<String, BpmEntry>,
    lookingUpBpm: Boolean,
    lookupIncomplete: Boolean,
    error: String?,
    onMultiplyBpm: (Track, Double) -> Unit,
    onAddBpm: (Track, Double) -> Unit,
    onSetBpm: (Track, Double?) -> Unit,
    onSetTempoHidden: (Track, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Track?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(32.dp)) {
        ScreenHeader(title = playlist.name, onBack = onBack)
        error?.let { ErrorText(it) }
        if (lookupIncomplete) ErrorText(stringResource(R.string.bpm_lookup_incomplete))
        if (tracks == null || lookingUpBpm) {
            if (lookingUpBpm) Text(stringResource(R.string.looking_up_bpm), style = MaterialTheme.typography.bodyMedium)
            if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        tracks?.let {
            LazyColumn {
                itemsIndexed(it) { index, track ->
                    SessionTrackRow(
                        number = index + 1,
                        track = track,
                        entry = bpmEntries[track.uri],
                        onHalve = { onMultiplyBpm(track, 0.5) },
                        onDouble = { onMultiplyBpm(track, 2.0) },
                        onDecrement = { onAddBpm(track, -1.0) },
                        onIncrement = { onAddBpm(track, 1.0) },
                        onEdit = { editing = track },
                        onReset = { onSetBpm(track, null) },
                        onSetHidden = { onSetTempoHidden(track, it) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { track ->
        BpmEditDialog(
            track = track,
            bpm = bpmEntries[track.uri]?.bpm,
            onConfirm = { bpm ->
                onSetBpm(track, bpm)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SessionTrackRow(
    number: Int,
    track: Track,
    entry: BpmEntry?,
    onHalve: () -> Unit,
    onDouble: () -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onSetHidden: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$number", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium)
            Text(track.artists, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            bpmSourceText(entry),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val hidden = entry?.hidden == true
        val bpm = entry?.bpm
        val canAdjust = bpm != null && !hidden
        OutlinedButton(onClick = onEdit, enabled = !hidden, modifier = Modifier.width(112.dp)) {
            Text(bpm?.let(::formatBpm) ?: "–", style = MaterialTheme.typography.titleLarge)
        }
        AdjustButton("÷2", enabled = canAdjust, onClick = onHalve)
        AdjustButton("×2", enabled = canAdjust, onClick = onDouble)
        AdjustButton("−1", enabled = canAdjust, onClick = onDecrement)
        AdjustButton("+1", enabled = canAdjust, onClick = onIncrement)
        TextButton(onClick = onReset, enabled = entry?.manualBpm != null && !hidden) {
            Text(stringResource(R.string.reset))
        }
        FilterChip(
            selected = hidden,
            onClick = { onSetHidden(!hidden) },
            label = { Text(stringResource(R.string.no_tempo)) },
        )
    }
}

@Composable
private fun AdjustButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(label)
    }
}

/** Where the BPM comes from, e.g. "ReccoBeats 148" or "edited · ReccoBeats 148". */
@Composable
private fun bpmSourceText(entry: BpmEntry?): String {
    if (entry == null) return ""
    val detected = entry.source?.let { "${it.label} ${entry.detectedBpm?.let(::formatBpm)}" }
        ?: stringResource(R.string.bpm_not_found)
    return if (entry.manualBpm != null) "${stringResource(R.string.bpm_edited)} · $detected" else detected
}

@Composable
private fun BpmEditDialog(track: Track, bpm: Double?, onConfirm: (Double?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(bpm?.let(::formatBpm).orEmpty()) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = text.isBlank() || (parsed != null && parsed > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.bpm)) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    stringResource(R.string.bpm_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parsed) }, enabled = valid) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
fun SessionTracksScreenPreview() {
    val tracks = listOf(
        Track("spotify:track:1", "Mr. Brightside", "The Killers", 222_000),
        Track("spotify:track:2", "Blinding Lights", "The Weeknd", 200_000),
        Track("spotify:track:3", "Some Local Song", "Unknown", 180_000),
    )
    TeamrowingDisplayTheme {
        SessionTracksScreen(
            playlist = Playlist("1", "Tuesday endurance", tracks.size),
            tracks = tracks,
            bpmEntries = mapOf(
                "spotify:track:1" to BpmEntry(148.03, BpmSource.RECCOBEATS, manualBpm = 37.0),
                "spotify:track:2" to BpmEntry(170.84, BpmSource.DEEZER),
                "spotify:track:3" to BpmEntry(hidden = true),
            ),
            lookingUpBpm = false,
            lookupIncomplete = false,
            error = null,
            onMultiplyBpm = { _, _ -> },
            onAddBpm = { _, _ -> },
            onSetBpm = { _, _ -> },
            onSetTempoHidden = { _, _ -> },
            onBack = {},
        )
    }
}
