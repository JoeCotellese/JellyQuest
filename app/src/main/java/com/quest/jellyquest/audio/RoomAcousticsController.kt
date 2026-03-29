package com.quest.jellyquest.audio

import com.quest.jellyquest.RoomGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages the EnvironmentalReverb lifecycle and cross-fades reverb parameters
 * when switching between theater presets. Uses [ReverbEffect] abstraction
 * for testability.
 */
class RoomAcousticsController(
    private val reverbEffect: ReverbEffect = AndroidReverbEffect(),
) {

    companion object {
        const val CROSS_FADE_MS = 500L
        const val FADE_STEP_MS = 16L // ~60fps parameter updates
    }

    private var enabled = false
    private var currentParams: ReverbParameters? = null
    private var fadeJob: Job? = null

    fun enable(audioSessionId: Int) {
        reverbEffect.create(audioSessionId)
        reverbEffect.setEnabled(true)
        enabled = true
    }

    fun disable() {
        if (!enabled) return
        fadeJob?.cancel()
        fadeJob = null
        reverbEffect.setEnabled(false)
        reverbEffect.release()
        enabled = false
        currentParams = null
    }

    /**
     * Cross-fade from the current reverb parameters to those computed for [room].
     * The fade runs over [CROSS_FADE_MS] at ~60fps parameter updates.
     * If a fade is already in progress, it is cancelled and the new fade starts
     * from the current intermediate values.
     */
    fun applyRoom(room: RoomGeometry, scope: CoroutineScope) {
        if (!enabled) return

        val targetParams = ReverbComputer.computeReverb(room)
        val startParams = currentParams

        // First application — set immediately, no cross-fade
        if (startParams == null) {
            reverbEffect.applyParameters(targetParams)
            currentParams = targetParams
            return
        }

        // Cancel any in-progress fade
        fadeJob?.cancel()

        fadeJob = scope.launch {
            val steps = (CROSS_FADE_MS / FADE_STEP_MS).toInt()
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                val intermediate = lerp(startParams, targetParams, t)
                reverbEffect.applyParameters(intermediate)
                currentParams = intermediate
                delay(FADE_STEP_MS)
            }
            // Ensure we land exactly on target
            reverbEffect.applyParameters(targetParams)
            currentParams = targetParams
        }
    }

    private fun lerp(a: ReverbParameters, b: ReverbParameters, t: Float): ReverbParameters {
        return ReverbParameters(
            decayTimeMs = lerp(a.decayTimeMs, b.decayTimeMs, t),
            roomLevel = lerp(a.roomLevel, b.roomLevel, t),
            roomHFLevel = lerp(a.roomHFLevel, b.roomHFLevel, t),
            reverbLevel = lerp(a.reverbLevel, b.reverbLevel, t),
            reverbDelayMs = lerp(a.reverbDelayMs, b.reverbDelayMs, t),
            diffusion = lerp(a.diffusion, b.diffusion, t),
            density = lerp(a.density, b.density, t),
        )
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt()

    private fun lerp(a: Short, b: Short, t: Float): Short =
        (a + (b - a) * t).toInt().toShort()
}
