package de.gregoire.teamrowingdisplay.session

import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.sync.SessionUpdate
import kotlin.math.ceil
import kotlin.math.floor

/** A track on the session graph; [tempo] is null for a track without a rowing tempo yet, which gets no bar. */
data class GraphTrack(val startMs: Long, val durationMs: Long, val tempo: Double?) {
    val endMs: Long
        get() = startMs + durationMs
}

/**
 * The session's tracks over its rowing time, as the remote app planned them, and where the session
 * stands. "No tempo" tracks are stretching, which isn't rowing time, so they are left out.
 */
data class SessionTimeline(
    val tracks: List<GraphTrack>,
    /** The current track's index in [tracks]. */
    val currentIndex: Int,
) {
    val totalMs: Long = tracks.sumOf { it.durationMs }

    /** The slowest and fastest rowing tempo; a timeline has at least one. */
    val tempoRange: ClosedFloatingPointRange<Double> = tracks.mapNotNull { it.tempo }.let { it.min()..it.max() }

    /** Where the session stands at [positionMs] in the current track: the earlier tracks' length plus the position. */
    fun cursorMs(positionMs: Long): Long {
        val current = tracks[currentIndex]
        return current.startMs + positionMs.coerceIn(0, current.durationMs)
    }
}

/**
 * The timeline of [session] while [currentUri] plays, with tempos from [entries], or null if the graph
 * has nothing to show: the track isn't in the session, is a "No tempo" track, or no track has a rowing
 * tempo yet. [upcomingCount] is the length of the queue, which tells a track that is in the session more
 * than once apart; while the queue still belongs to the previous track, the first occurrence is taken.
 */
fun sessionTimeline(
    session: SessionUpdate,
    entries: Map<String, BpmEntry>,
    currentUri: String,
    upcomingCount: Int,
): SessionTimeline? {
    if (entries[currentUri]?.hidden == true) return null
    val tracks = session.tracks
    val index = (tracks.size - upcomingCount - 1).takeIf { tracks.getOrNull(it)?.uri == currentUri }
        ?: tracks.indexOfFirst { it.uri == currentUri }
    if (index < 0) return null

    var startMs = 0L
    var currentIndex = 0
    val graphTracks = mutableListOf<GraphTrack>()
    tracks.forEachIndexed { i, track ->
        val entry = entries[track.uri]
        if (entry?.hidden == true) return@forEachIndexed
        if (i == index) currentIndex = graphTracks.size
        graphTracks += GraphTrack(startMs, track.durationMs, rowingTempo(entry))
        startMs += track.durationMs
    }
    if (graphTracks.none { it.tempo != null }) return null
    return SessionTimeline(graphTracks, currentIndex)
}

/**
 * The rowing tempo of a track, or null if it has none yet. Lookups give the song's BPM, usually two or
 * four times the rowing tempo, so only values up to [MAX_ROWING_TEMPO] count, as in the remote app.
 */
fun rowingTempo(entry: BpmEntry?): Double? = entry?.bpm?.takeIf { it <= MAX_ROWING_TEMPO }

const val MAX_ROWING_TEMPO = 40.0

/**
 * The tempos the session graph spans for [range]: from a little below the slowest tempo, so its bar is
 * visible, up to the fastest one.
 */
fun graphTempoRange(range: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> =
    floor(range.start) - ROOM_BELOW_SLOWEST..ceil(range.endInclusive)

private const val ROOM_BELOW_SLOWEST = 2.0
