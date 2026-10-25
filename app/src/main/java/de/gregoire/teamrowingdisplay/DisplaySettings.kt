package de.gregoire.teamrowingdisplay

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Space for the tempo number. Two-digit tempos are limited by the row height rather than the width,
 * so both are set per size.
 */
enum class TempoSize(val width: Dp, val heightFraction: Float, @param:StringRes val label: Int) {
    SMALL(260.dp, 0.6f, R.string.size_small),
    MEDIUM(360.dp, 0.8f, R.string.size_medium),
    LARGE(480.dp, 1f, R.string.size_large),
}

/** How the height is split between the current and the next track. */
enum class TrackSplit(val currentWeight: Float, val label: String) {
    SPLIT_60_40(0.6f, "60 / 40"),
    SPLIT_70_30(0.7f, "70 / 30"),
    SPLIT_80_20(0.8f, "80 / 20"),
}

enum class DisplayColorScheme(@param:StringRes val label: Int, val dark: Boolean) {
    DARK(R.string.color_scheme_dark, dark = true),
    LIGHT(R.string.color_scheme_light, dark = false),
    HIGH_CONTRAST(R.string.color_scheme_high_contrast, dark = true),
}

/** Accent colors for the tempo and the progress bar, each in a variant for dark and for light backgrounds. */
enum class AccentColor(val onDark: Color, val onLight: Color) {
    AMBER(Color(0xFFFFC107), Color(0xFFB26A00)),
    ORANGE(Color(0xFFFF9100), Color(0xFFD84315)),
    RED(Color(0xFFFF5252), Color(0xFFC62828)),
    MAGENTA(Color(0xFFFF4081), Color(0xFFAD1457)),
    BLUE(Color(0xFF448AFF), Color(0xFF1565C0)),
    CYAN(Color(0xFF18FFFF), Color(0xFF00838F)),
    GREEN(Color(0xFF69F0AE), Color(0xFF1B7F3B)),
    NEUTRAL(Color.White, Color.Black),
}

data class DisplaySettings(
    val showNext: Boolean = true,
    val showArtist: Boolean = true,
    val showProgressBar: Boolean = true,
    val showTimes: Boolean = true,
    val showNextDuration: Boolean = true,
    val showTempoLabel: Boolean = false,
    val tempoLabel: String = "Tempo",
    val tempoSize: TempoSize = TempoSize.MEDIUM,
    val split: TrackSplit = TrackSplit.SPLIT_60_40,
    val colorScheme: DisplayColorScheme = DisplayColorScheme.DARK,
    val accent: AccentColor = AccentColor.AMBER,
)

class DisplayColors(
    val background: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val progressTrack: Color,
    val error: Color,
)

fun DisplaySettings.colors(): DisplayColors {
    val accent = if (colorScheme.dark) accent.onDark else accent.onLight
    return when (colorScheme) {
        DisplayColorScheme.DARK -> DisplayColors(
            background = Color.Black,
            primary = Color.White,
            secondary = Color(0xFFB3B3B3),
            accent = accent,
            progressTrack = Color(0xFF333333),
            error = Color(0xFFFF6B6B),
        )
        DisplayColorScheme.LIGHT -> DisplayColors(
            background = Color.White,
            primary = Color(0xFF111111),
            secondary = Color(0xFF555555),
            accent = accent,
            progressTrack = Color(0xFFDDDDDD),
            error = Color(0xFFC62828),
        )
        DisplayColorScheme.HIGH_CONTRAST -> DisplayColors(
            background = Color.Black,
            primary = Color.White,
            secondary = Color.White,
            accent = accent,
            progressTrack = Color(0xFF555555),
            error = Color(0xFFFF6B6B),
        )
    }
}

/** Stores the display settings in shared preferences; they apply to all sessions. */
class DisplaySettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DisplaySettings> = _settings.asStateFlow()

    fun update(transform: (DisplaySettings) -> DisplaySettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        save(updated)
    }

    private fun load(): DisplaySettings {
        val defaults = DisplaySettings()
        return DisplaySettings(
            showNext = prefs.getBoolean(KEY_SHOW_NEXT, defaults.showNext),
            showArtist = prefs.getBoolean(KEY_SHOW_ARTIST, defaults.showArtist),
            showProgressBar = prefs.getBoolean(KEY_SHOW_PROGRESS_BAR, defaults.showProgressBar),
            showTimes = prefs.getBoolean(KEY_SHOW_TIMES, defaults.showTimes),
            showNextDuration = prefs.getBoolean(KEY_SHOW_NEXT_DURATION, defaults.showNextDuration),
            showTempoLabel = prefs.getBoolean(KEY_SHOW_TEMPO_LABEL, defaults.showTempoLabel),
            tempoLabel = prefs.getString(KEY_TEMPO_LABEL, null) ?: defaults.tempoLabel,
            tempoSize = enumPref(KEY_TEMPO_SIZE, defaults.tempoSize),
            split = enumPref(KEY_SPLIT, defaults.split),
            colorScheme = enumPref(KEY_COLOR_SCHEME, defaults.colorScheme),
            accent = enumPref(KEY_ACCENT, defaults.accent),
        )
    }

    private fun save(settings: DisplaySettings) {
        prefs.edit {
            putBoolean(KEY_SHOW_NEXT, settings.showNext)
            putBoolean(KEY_SHOW_ARTIST, settings.showArtist)
            putBoolean(KEY_SHOW_PROGRESS_BAR, settings.showProgressBar)
            putBoolean(KEY_SHOW_TIMES, settings.showTimes)
            putBoolean(KEY_SHOW_NEXT_DURATION, settings.showNextDuration)
            putBoolean(KEY_SHOW_TEMPO_LABEL, settings.showTempoLabel)
            putString(KEY_TEMPO_LABEL, settings.tempoLabel)
            putString(KEY_TEMPO_SIZE, settings.tempoSize.name)
            putString(KEY_SPLIT, settings.split.name)
            putString(KEY_COLOR_SCHEME, settings.colorScheme.name)
            putString(KEY_ACCENT, settings.accent.name)
        }
    }

    /** Reads an enum by name, falling back to the default for missing or unknown values. */
    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private companion object {
        const val PREFS_NAME = "display_settings"
        const val KEY_SHOW_NEXT = "show_next"
        const val KEY_SHOW_ARTIST = "show_artist"
        const val KEY_SHOW_PROGRESS_BAR = "show_progress_bar"
        const val KEY_SHOW_TIMES = "show_times"
        const val KEY_SHOW_NEXT_DURATION = "show_next_duration"
        const val KEY_SHOW_TEMPO_LABEL = "show_tempo_label"
        const val KEY_TEMPO_LABEL = "tempo_label"
        const val KEY_TEMPO_SIZE = "tempo_size"
        const val KEY_SPLIT = "split"
        const val KEY_COLOR_SCHEME = "color_scheme"
        const val KEY_ACCENT = "accent"
    }
}
