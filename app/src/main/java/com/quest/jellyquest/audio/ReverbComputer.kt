package com.quest.jellyquest.audio

import com.quest.jellyquest.RoomGeometry

/**
 * Reverb parameters for Android's EnvironmentalReverb audio effect.
 * All values use EnvironmentalReverb's native units.
 */
data class ReverbParameters(
    val decayTimeMs: Int,       // Reverb decay time in milliseconds
    val roomLevel: Short,       // Master room level in millibels [-9000, 0]
    val roomHFLevel: Short,     // High-frequency room level in millibels [-9000, 0]
    val reverbLevel: Short,     // Late reverb level in millibels [-9000, 0]
    val reverbDelayMs: Int,     // Late reverb delay in milliseconds
    val diffusion: Short,       // Echo density, permilles [0, 1000]
    val density: Short,         // Modal density, permilles [0, 1000]
) {
    /**
     * Compute wet/dry mix ratio from room and reverb levels.
     * Converts from millibels to linear ratio: wet / (wet + dry).
     */
    fun wetMixRatio(): Float {
        val dryLinear = Math.pow(10.0, roomLevel / 2000.0).toFloat()
        val wetLinear = Math.pow(10.0, reverbLevel / 2000.0).toFloat()
        return wetLinear / (wetLinear + dryLinear)
    }
}

/**
 * Pure computation: maps room geometry to reverb parameters via piecewise
 * linear interpolation across four theater-sized anchor points.
 *
 * No Android dependencies — fully unit-testable.
 */
object ReverbComputer {

    // Anchor points: volume (m³) → reverb parameters, one per theater preset.
    // Tuned for "felt, not noticed" cinema acoustics.
    private data class Anchor(val volume: Float, val params: ReverbParameters)

    private val anchors = listOf(
        // Screening Room (~1887 m³): tight, dry reverb — ~12% wet
        Anchor(1887f, ReverbParameters(
            decayTimeMs = 400,
            roomLevel = -300,
            roomHFLevel = -400,
            reverbLevel = -2500,
            reverbDelayMs = 15,
            diffusion = 850,
            density = 900,
        )),
        // Multiplex (~6042 m³): moderate, warm reverb — ~20% wet
        Anchor(6042f, ReverbParameters(
            decayTimeMs = 900,
            roomLevel = -300,
            roomHFLevel = -600,
            reverbLevel = -1500,
            reverbDelayMs = 30,
            diffusion = 750,
            density = 800,
        )),
        // PLF (~10030 m³): spacious, clear reverb — ~27% wet
        Anchor(10030f, ReverbParameters(
            decayTimeMs = 1400,
            roomLevel = -300,
            roomHFLevel = -800,
            reverbLevel = -1050,
            reverbDelayMs = 50,
            diffusion = 650,
            density = 700,
        )),
        // IMAX (~22458 m³): vast, enveloping reverb — ~33% wet
        Anchor(22458f, ReverbParameters(
            decayTimeMs = 2000,
            roomLevel = -300,
            roomHFLevel = -1000,
            reverbLevel = -850,
            reverbDelayMs = 70,
            diffusion = 550,
            density = 600,
        )),
    )

    /** Compute room volume in cubic meters (average of front/back width for trapezoid). */
    fun roomVolume(room: RoomGeometry): Float {
        val avgWidth = (room.widthFront + room.widthBack) / 2f
        return avgWidth * room.depth * room.ceilingHeight
    }

    /** Map room geometry to reverb parameters via piecewise linear interpolation. */
    fun computeReverb(room: RoomGeometry): ReverbParameters {
        val volume = roomVolume(room)

        // Clamp below smallest anchor
        if (volume <= anchors.first().volume) return anchors.first().params
        // Clamp above largest anchor
        if (volume >= anchors.last().volume) return anchors.last().params

        // Find bounding anchors and interpolate
        for (i in 0 until anchors.size - 1) {
            val lo = anchors[i]
            val hi = anchors[i + 1]
            if (volume <= hi.volume) {
                val t = (volume - lo.volume) / (hi.volume - lo.volume)
                return lerp(lo.params, hi.params, t)
            }
        }

        return anchors.last().params
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
