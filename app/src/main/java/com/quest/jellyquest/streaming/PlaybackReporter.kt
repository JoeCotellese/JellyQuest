package com.quest.jellyquest.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Reads playback position from ExoPlayer and reports it to the Jellyfin server.
 * Owns the periodic reporting coroutine and resume-position decision logic.
 *
 * All public methods must be called from the same dispatcher (typically Dispatchers.Main
 * via activityScope) to avoid races on mutable state.
 */
class PlaybackReporter(
    private val jellyfinClient: JellyfinClient,
    private val positionProvider: PositionProvider,
    private val scope: CoroutineScope,
) {

    private var currentItemId: UUID? = null
    private var reportingJob: Job? = null

    /** Begin reporting for the given item. Stops any previous reporting session. */
    suspend fun startReporting(itemId: UUID) {
        stopReporting()
        currentItemId = itemId
        val positionTicks = positionProvider.currentPositionMs() * TICKS_PER_MS
        jellyfinClient.reportPlaybackStart(itemId, positionTicks)
        Log.i(TAG, "Started reporting for $itemId at ${positionTicks / TICKS_PER_MS}ms")
        launchPeriodicReporting(itemId)
    }

    /** Stop reporting and send a final stopped report. */
    suspend fun stopReporting() {
        val itemId = currentItemId ?: return
        reportingJob?.cancel()
        reportingJob = null
        val positionTicks = positionProvider.currentPositionMs() * TICKS_PER_MS
        jellyfinClient.reportPlaybackStopped(itemId, positionTicks)
        currentItemId = null
        Log.i(TAG, "Stopped reporting for $itemId at ${positionTicks / TICKS_PER_MS}ms")
    }

    /** Stop reporting with a pre-captured position (use when player is already stopped). */
    suspend fun stopReportingAtPosition(positionMs: Long) {
        val itemId = currentItemId ?: return
        reportingJob?.cancel()
        reportingJob = null
        val positionTicks = positionMs * TICKS_PER_MS
        jellyfinClient.reportPlaybackStopped(itemId, positionTicks)
        currentItemId = null
        Log.i(TAG, "Stopped reporting for $itemId at ${positionMs}ms (pre-captured)")
    }

    /** Report current position immediately (e.g., on pause). */
    suspend fun reportCurrentPosition() {
        val itemId = currentItemId ?: return
        val positionTicks = positionProvider.currentPositionMs() * TICKS_PER_MS
        jellyfinClient.reportPlaybackProgress(itemId, positionTicks)
    }

    /** Pause periodic reporting and send a progress update. Does NOT send a stopped event. */
    suspend fun pauseReporting() {
        val itemId = currentItemId ?: return
        reportingJob?.cancel()
        reportingJob = null
        val positionTicks = positionProvider.currentPositionMs() * TICKS_PER_MS
        jellyfinClient.reportPlaybackProgress(itemId, positionTicks, isPaused = true)
        Log.i(TAG, "Paused reporting for $itemId at ${positionTicks / TICKS_PER_MS}ms")
    }

    /** Resume periodic reporting after a pause. Sends a progress update immediately. */
    suspend fun resumeReporting() {
        val itemId = currentItemId ?: return
        if (reportingJob != null) return // Already reporting
        val positionTicks = positionProvider.currentPositionMs() * TICKS_PER_MS
        jellyfinClient.reportPlaybackProgress(itemId, positionTicks)
        Log.i(TAG, "Resumed reporting for $itemId at ${positionTicks / TICKS_PER_MS}ms")
        launchPeriodicReporting(itemId)
    }

    private fun launchPeriodicReporting(itemId: UUID) {
        reportingJob = scope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                val ticks = positionProvider.currentPositionMs() * TICKS_PER_MS
                jellyfinClient.reportPlaybackProgress(itemId, ticks)
            }
        }
    }

    companion object {
        private const val TAG = "VirtualMonitor"
        const val TICKS_PER_MS = 10_000L
        private const val NEAR_END_THRESHOLD_MS = 30_000L
        private const val SHORT_CONTENT_THRESHOLD_MS = 60_000L
        const val REPORT_INTERVAL_MS = 10_000L

        /**
         * Decide where to resume playback. Returns 0 to start from the beginning.
         * - If positionTicks <= 0: start from beginning
         * - If content > 60s and within 30s of end: start from beginning (treat as rewatch)
         * - If content <= 60s: always resume (skip near-end logic)
         * - Otherwise: convert ticks to milliseconds and resume there
         */
        fun computeResumePositionMs(playbackPositionTicks: Long, runTimeTicks: Long): Long {
            if (playbackPositionTicks <= 0) return 0
            val positionMs = playbackPositionTicks / TICKS_PER_MS
            val durationMs = runTimeTicks / TICKS_PER_MS
            if (durationMs <= 0) return positionMs
            val remainingMs = durationMs - positionMs
            if (durationMs > SHORT_CONTENT_THRESHOLD_MS && remainingMs <= NEAR_END_THRESHOLD_MS) return 0
            return positionMs
        }

        /** Compute what percentage of the content has been watched (0-100). */
        fun computeProgressPercent(positionTicks: Long, runtimeTicks: Long): Int {
            if (runtimeTicks <= 0 || positionTicks <= 0) return 0
            return (positionTicks * 100 / runtimeTicks).toInt().coerceIn(0, 100)
        }

        /** Compute how many minutes remain. */
        fun computeRemainingMinutes(positionTicks: Long, runtimeTicks: Long): Int {
            if (runtimeTicks <= 0 || positionTicks <= 0) return 0
            val remainingMs = (runtimeTicks - positionTicks) / TICKS_PER_MS
            return (remainingMs / 60_000).toInt()
        }

        /** True if >= 90% of the content has been watched. */
        fun isFullyWatched(positionTicks: Long, runtimeTicks: Long): Boolean {
            return computeProgressPercent(positionTicks, runtimeTicks) >= 90
        }
    }
}

/** Provides the current playback position in milliseconds. */
fun interface PositionProvider {
    fun currentPositionMs(): Long
}
