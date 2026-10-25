package de.gregoire.teamrowingdisplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.spotify.PlaybackState
import de.gregoire.teamrowingdisplay.spotify.Track
import de.gregoire.teamrowingdisplay.ui.theme.TeamrowingDisplayTheme

/** Display settings on the left, a live preview of the display on the right. */
@Composable
fun DisplaySettingsScreen(
    settings: DisplaySettings,
    playback: PlaybackState?,
    next: Track?,
    bpmEntries: Map<String, BpmEntry>,
    onChange: ((DisplaySettings) -> DisplaySettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Row(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.4f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Text(
                    stringResource(R.string.display_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SectionTitle(stringResource(R.string.settings_section_show))
            SwitchRow(stringResource(R.string.show_next), settings.showNext) { value ->
                onChange { it.copy(showNext = value) }
            }
            SwitchRow(stringResource(R.string.show_artist), settings.showArtist) { value ->
                onChange { it.copy(showArtist = value) }
            }
            SwitchRow(stringResource(R.string.show_progress_bar), settings.showProgressBar) { value ->
                onChange { it.copy(showProgressBar = value) }
            }
            SwitchRow(stringResource(R.string.show_times), settings.showTimes) { value ->
                onChange { it.copy(showTimes = value) }
            }
            SwitchRow(
                stringResource(R.string.show_next_duration),
                settings.showNextDuration,
                enabled = settings.showNext,
            ) { value ->
                onChange { it.copy(showNextDuration = value) }
            }
            SwitchRow(stringResource(R.string.show_tempo_label), settings.showTempoLabel) { value ->
                onChange { it.copy(showTempoLabel = value) }
            }
            if (settings.showTempoLabel) {
                OutlinedTextField(
                    value = settings.tempoLabel,
                    onValueChange = { value -> onChange { it.copy(tempoLabel = value) } },
                    label = { Text(stringResource(R.string.tempo_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionTitle(stringResource(R.string.settings_section_sizes))
            ChoiceRow(
                label = stringResource(R.string.tempo_size),
                options = TempoSize.entries,
                selected = settings.tempoSize,
                optionLabel = { stringResource(it.label) },
                onSelect = { value -> onChange { it.copy(tempoSize = value) } },
            )
            ChoiceRow(
                label = stringResource(R.string.track_split),
                options = TrackSplit.entries,
                selected = settings.split,
                optionLabel = { it.label },
                onSelect = { value -> onChange { it.copy(split = value) } },
                enabled = settings.showNext,
            )

            SectionTitle(stringResource(R.string.settings_section_colors))
            ChoiceRow(
                label = stringResource(R.string.color_scheme),
                options = DisplayColorScheme.entries,
                selected = settings.colorScheme,
                optionLabel = { stringResource(it.label) },
                onSelect = { value -> onChange { it.copy(colorScheme = value) } },
            )
            Text(stringResource(R.string.accent_color), style = MaterialTheme.typography.bodyLarge)
            AccentColorPicker(settings) { value -> onChange { it.copy(accent = value) } }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { onChange { DisplaySettings() } }) {
                Text(stringResource(R.string.reset_display_settings))
            }
        }

        val hasPlayback = playback?.track != null
        ScaledDisplayPreview(
            playback = if (hasPlayback) playback else SampleDisplayData.playback,
            next = if (hasPlayback) next else SampleDisplayData.next,
            bpmEntries = if (hasPlayback) bpmEntries else SampleDisplayData.bpmEntries,
            settings = settings,
            modifier = Modifier.weight(0.6f).align(Alignment.CenterVertically),
        )
    }
}

/** Lays out the display at full screen size and scales it down, so the preview looks exactly like the display. */
@Composable
private fun ScaledDisplayPreview(
    playback: PlaybackState?,
    next: Track?,
    bpmEntries: Map<String, BpmEntry>,
    settings: DisplaySettings,
    modifier: Modifier = Modifier,
) {
    val screenSize = LocalWindowInfo.current.containerSize
    val (screenWidth, screenHeight) = with(LocalDensity.current) {
        // The window size is unknown in Android Studio's preview
        if (screenSize.width > 0 && screenSize.height > 0) screenSize.width.toDp() to screenSize.height.toDp()
        else 1280.dp to 800.dp
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(screenWidth / screenHeight)
            .clip(RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val scale = maxWidth / screenWidth
        DisplayContent(
            playback = playback,
            next = next,
            bpmEntries = bpmEntries,
            settings = settings,
            modifier = Modifier
                .requiredSize(screenWidth, screenHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    enabled = enabled,
                ) {
                    Text(optionLabel(option))
                }
            }
        }
    }
}

/** Color swatches, shown in the variant that matches the selected color scheme's background. */
@Composable
private fun AccentColorPicker(settings: DisplaySettings, onSelect: (AccentColor) -> Unit) {
    val background = settings.colors().background
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentColor.entries.forEach { accent ->
            val selected = accent == settings.accent
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(background)
                    .border(
                        BorderStroke(if (selected) 4.dp else 1.dp, MaterialTheme.colorScheme.outline),
                        CircleShape,
                    )
                    .clickable { onSelect(accent) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (settings.colorScheme.dark) accent.onDark else accent.onLight)
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
fun DisplaySettingsScreenPreview() {
    TeamrowingDisplayTheme {
        DisplaySettingsScreen(
            settings = DisplaySettings(showTempoLabel = true),
            playback = null,
            next = null,
            bpmEntries = emptyMap(),
            onChange = {},
            onBack = {},
        )
    }
}
