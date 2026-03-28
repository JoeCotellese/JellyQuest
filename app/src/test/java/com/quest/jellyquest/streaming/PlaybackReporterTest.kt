package com.quest.jellyquest.streaming

import io.mockk.Ordering
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {

    // --- Resume position logic ---

    @Test
    fun `computeResumePositionMs returns 0 when positionTicks is 0`() {
        assertEquals(0L, PlaybackReporter.computeResumePositionMs(0, 72_000_000_000L))
    }

    @Test
    fun `computeResumePositionMs returns 0 when positionTicks is negative`() {
        assertEquals(0L, PlaybackReporter.computeResumePositionMs(-1, 72_000_000_000L))
    }

    @Test
    fun `computeResumePositionMs returns position in ms when in middle of content`() {
        // 500 seconds in = 5_000_000_000 ticks, 2 hour movie = 72_000_000_000 ticks
        val positionMs = PlaybackReporter.computeResumePositionMs(5_000_000_000L, 72_000_000_000L)
        assertEquals(500_000L, positionMs) // 500 seconds = 500,000ms
    }

    @Test
    fun `computeResumePositionMs returns 0 when within 30s of end`() {
        // 2 hour movie = 7200s = 72_000_000_000 ticks
        // Position at 7180s = 71_800_000_000 ticks (20s from end)
        val positionMs = PlaybackReporter.computeResumePositionMs(71_800_000_000L, 72_000_000_000L)
        assertEquals(0L, positionMs) // Should restart
    }

    @Test
    fun `computeResumePositionMs returns position for short content under 60s near end`() {
        // 45s content = 450_000_000 ticks
        // Position at 40s = 400_000_000 ticks (5s from end)
        val positionMs = PlaybackReporter.computeResumePositionMs(400_000_000L, 450_000_000L)
        assertEquals(40_000L, positionMs) // Should resume, not restart
    }

    @Test
    fun `computeResumePositionMs returns position when runTimeTicks is 0`() {
        // Unknown duration — resume anyway
        val positionMs = PlaybackReporter.computeResumePositionMs(5_000_000_000L, 0)
        assertEquals(500_000L, positionMs)
    }

    @Test
    fun `computeResumePositionMs converts ticks to ms correctly`() {
        // 1 second = 10_000_000 ticks = 1000ms
        assertEquals(1_000L, PlaybackReporter.computeResumePositionMs(10_000_000L, 100_000_000_000L))
    }

    // --- Progress computation ---

    @Test
    fun `computeProgressPercent returns 0 for no position`() {
        assertEquals(0, PlaybackReporter.computeProgressPercent(0, 72_000_000_000L))
    }

    @Test
    fun `computeProgressPercent returns correct percentage`() {
        // Half of 2hr movie
        assertEquals(50, PlaybackReporter.computeProgressPercent(36_000_000_000L, 72_000_000_000L))
    }

    @Test
    fun `computeProgressPercent returns 0 when runtimeTicks is 0`() {
        assertEquals(0, PlaybackReporter.computeProgressPercent(5_000_000_000L, 0))
    }

    @Test
    fun `computeRemainingMinutes returns correct value`() {
        // 2hr movie, 30 min in -> 90 min remaining
        // 30 min = 18_000_000_000 ticks, 2hr = 72_000_000_000 ticks
        assertEquals(90, PlaybackReporter.computeRemainingMinutes(18_000_000_000L, 72_000_000_000L))
    }

    @Test
    fun `isFullyWatched returns true at 90 percent threshold`() {
        // 90% of 72_000_000_000 = 64_800_000_000
        assertTrue(PlaybackReporter.isFullyWatched(64_800_000_000L, 72_000_000_000L))
    }

    @Test
    fun `isFullyWatched returns false at 89 percent`() {
        // 89% of 72_000_000_000 = 64_080_000_000
        assertFalse(PlaybackReporter.isFullyWatched(64_080_000_000L, 72_000_000_000L))
    }

    // --- Periodic reporting ---

    @Test
    fun `startReporting calls reportPlaybackStart`() = runTest {
        val client = mockk<JellyfinClient>()
        val itemId = UUID.randomUUID()
        coEvery { client.reportPlaybackStart(any(), any()) } just Runs
        coEvery { client.reportPlaybackProgress(any(), any(), any()) } just Runs
        coEvery { client.reportPlaybackStopped(any(), any()) } just Runs

        val reporter = PlaybackReporter(
            jellyfinClient = client,
            positionProvider = { 5_000L },
            scope = backgroundScope,
        )

        reporter.startReporting(itemId)

        coVerify { client.reportPlaybackStart(itemId, 50_000_000L) } // 5000ms * 10_000
    }

    @Test
    fun `periodic progress reported every 10 seconds`() = runTest {
        val client = mockk<JellyfinClient>()
        val itemId = UUID.randomUUID()
        var positionMs = 0L
        coEvery { client.reportPlaybackStart(any(), any()) } just Runs
        coEvery { client.reportPlaybackProgress(any(), any(), any()) } just Runs
        coEvery { client.reportPlaybackStopped(any(), any()) } just Runs

        val reporter = PlaybackReporter(
            jellyfinClient = client,
            positionProvider = { positionMs },
            scope = backgroundScope,
        )

        reporter.startReporting(itemId)

        // Advance 10s — first periodic report
        positionMs = 10_000L
        advanceTimeBy(10_001)
        coVerify(exactly = 1) { client.reportPlaybackProgress(itemId, 100_000_000L, any()) }

        // Advance another 10s — second periodic report
        positionMs = 20_000L
        advanceTimeBy(10_000)
        coVerify(exactly = 2) { client.reportPlaybackProgress(itemId, any(), any()) }
    }

    @Test
    fun `stopReporting calls reportPlaybackStopped with current position`() = runTest {
        val client = mockk<JellyfinClient>()
        val itemId = UUID.randomUUID()
        coEvery { client.reportPlaybackStart(any(), any()) } just Runs
        coEvery { client.reportPlaybackProgress(any(), any(), any()) } just Runs
        coEvery { client.reportPlaybackStopped(any(), any()) } just Runs

        val reporter = PlaybackReporter(
            jellyfinClient = client,
            positionProvider = { 30_000L },
            scope = backgroundScope,
        )

        reporter.startReporting(itemId)
        reporter.stopReporting()

        coVerify { client.reportPlaybackStopped(itemId, 300_000_000L) } // 30_000ms * 10_000
    }

    @Test
    fun `reportCurrentPosition reports progress immediately`() = runTest {
        val client = mockk<JellyfinClient>()
        val itemId = UUID.randomUUID()
        coEvery { client.reportPlaybackStart(any(), any()) } just Runs
        coEvery { client.reportPlaybackProgress(any(), any(), any()) } just Runs
        coEvery { client.reportPlaybackStopped(any(), any()) } just Runs

        val reporter = PlaybackReporter(
            jellyfinClient = client,
            positionProvider = { 15_000L },
            scope = backgroundScope,
        )

        reporter.startReporting(itemId)
        reporter.reportCurrentPosition()

        coVerify { client.reportPlaybackProgress(itemId, 150_000_000L, any()) }
    }

    @Test
    fun `stopReporting with no active session does not call API`() = runTest {
        val client = mockk<JellyfinClient>()
        coEvery { client.reportPlaybackStopped(any(), any()) } just Runs

        val reporter = PlaybackReporter(
            jellyfinClient = client,
            positionProvider = { 0L },
            scope = backgroundScope,
        )

        reporter.stopReporting()

        coVerify(exactly = 0) { client.reportPlaybackStopped(any(), any()) }
    }

    @Test
    fun `double start sends stopped for first item before starting second`() = runTest {
        val client = mockk<JellyfinClient>()
        val itemA = UUID.randomUUID()
        val itemB = UUID.randomUUID()
        coEvery { client.reportPlaybackStart(any(), any()) } just Runs
        coEvery { client.reportPlaybackProgress(any(), any(), any()) } just Runs
        coEvery { client.reportPlaybackStopped(any(), any()) } just Runs

        val reporter = PlaybackReporter(
            jellyfinClient = client,
            positionProvider = { 10_000L },
            scope = backgroundScope,
        )

        reporter.startReporting(itemA)
        reporter.startReporting(itemB)

        coVerify(ordering = Ordering.ORDERED) {
            client.reportPlaybackStart(itemA, any())
            client.reportPlaybackStopped(itemA, any())
            client.reportPlaybackStart(itemB, any())
        }
    }

    @Test
    fun `pauseReporting sends progress with isPaused true and cancels periodic job`() = runTest {
        val client = mockk<JellyfinClient>()
        val itemId = UUID.randomUUID()
        coEvery { client.reportPlaybackStart(any(), any()) } just Runs
        coEvery { client.reportPlaybackProgress(any(), any(), any()) } just Runs
        coEvery { client.reportPlaybackStopped(any(), any()) } just Runs

        val reporter = PlaybackReporter(
            jellyfinClient = client,
            positionProvider = { 20_000L },
            scope = backgroundScope,
        )

        reporter.startReporting(itemId)
        reporter.pauseReporting()

        coVerify { client.reportPlaybackProgress(itemId, 200_000_000L, isPaused = true) }

        // Verify periodic reporting stopped — advance time and check no more progress calls
        advanceTimeBy(30_000)
        coVerify(exactly = 1) { client.reportPlaybackProgress(itemId, any(), any()) }
    }

    @Test
    fun `resumeReporting restarts periodic reporting`() = runTest {
        val client = mockk<JellyfinClient>()
        val itemId = UUID.randomUUID()
        var positionMs = 20_000L
        coEvery { client.reportPlaybackStart(any(), any()) } just Runs
        coEvery { client.reportPlaybackProgress(any(), any(), any()) } just Runs
        coEvery { client.reportPlaybackStopped(any(), any()) } just Runs

        val reporter = PlaybackReporter(
            jellyfinClient = client,
            positionProvider = { positionMs },
            scope = backgroundScope,
        )

        reporter.startReporting(itemId)
        reporter.pauseReporting()
        reporter.resumeReporting()

        // Advance 10s — should get a periodic report
        positionMs = 30_000L
        advanceTimeBy(10_001)
        coVerify(atLeast = 2) { client.reportPlaybackProgress(itemId, any(), any()) }
    }
}
