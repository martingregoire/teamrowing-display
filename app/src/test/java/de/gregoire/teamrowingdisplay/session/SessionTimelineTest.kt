package de.gregoire.teamrowingdisplay.session

import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.sync.SessionTrack
import de.gregoire.teamrowingdisplay.sync.SessionUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTimelineTest {

    private fun session(vararg uris: String) =
        SessionUpdate("spotify:playlist:tr", uris.map { SessionTrack(it, MINUTE * 3) })

    private val tempos = mapOf(
        "a" to BpmEntry(manualBpm = 20.0),
        "b" to BpmEntry(manualBpm = 22.0),
        "c" to BpmEntry(manualBpm = 24.0),
    )

    @Test
    fun `the cursor is the earlier tracks' length plus the position`() {
        val timeline = sessionTimeline(session("a", "b", "c"), tempos, "c", upcomingCount = 0)!!

        assertEquals(2, timeline.currentIndex)
        assertEquals(MINUTE * 6 + 30_000, timeline.cursorMs(30_000))
        assertEquals(MINUTE * 9, timeline.totalMs)
    }

    @Test
    fun `no tempo tracks are left out of the rowing time`() {
        val entries = tempos + ("stretch" to BpmEntry(hidden = true))

        val timeline = sessionTimeline(session("a", "stretch", "b"), entries, "b", upcomingCount = 0)!!

        assertEquals(listOf(0L, MINUTE * 3), timeline.tracks.map { it.startMs })
        assertEquals(1, timeline.currentIndex)
        assertEquals(MINUTE * 6, timeline.totalMs)
    }

    @Test
    fun `there is no timeline while a no tempo track plays`() {
        val entries = tempos + ("stretch" to BpmEntry(hidden = true))

        assertNull(sessionTimeline(session("a", "stretch"), entries, "stretch", upcomingCount = 0))
    }

    @Test
    fun `there is no timeline for a track outside the session`() {
        assertNull(sessionTimeline(session("a", "b"), tempos, "other", upcomingCount = 0))
    }

    @Test
    fun `unprepared tracks keep their time without a tempo`() {
        val entries = tempos + ("song" to BpmEntry(detectedBpm = 92.0))

        val timeline = sessionTimeline(session("a", "song", "new", "b"), entries, "b", upcomingCount = 0)!!

        assertEquals(listOf(20.0, null, null, 22.0), timeline.tracks.map { it.tempo })
        assertEquals(MINUTE * 9, timeline.cursorMs(0))
    }

    @Test
    fun `there is no timeline without any rowing tempo`() {
        assertNull(sessionTimeline(session("x", "y"), emptyMap(), "x", upcomingCount = 1))
    }

    @Test
    fun `a repeated track is told apart by the queue`() {
        val timeline = sessionTimeline(session("a", "b", "a", "c"), tempos, "a", upcomingCount = 1)!!

        assertEquals(2, timeline.currentIndex)
    }

    @Test
    fun `a repeated track falls back to its first occurrence with a stale queue`() {
        val timeline = sessionTimeline(session("a", "b", "a", "c"), tempos, "a", upcomingCount = 0)!!

        assertEquals(0, timeline.currentIndex)
    }

    @Test
    fun `the cursor stays within the current track`() {
        val timeline = sessionTimeline(session("a", "b"), tempos, "a", upcomingCount = 1)!!

        assertEquals(MINUTE * 3, timeline.cursorMs(MINUTE * 5))
    }

    @Test
    fun `the graph spans from a little below the slowest tempo to the fastest`() {
        assertEquals(18.0..26.0, graphTempoRange(20.0..26.0))
    }

    @Test
    fun `the graph spans whole tempos`() {
        assertEquals(19.0..24.0, graphTempoRange(21.5..23.5))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
