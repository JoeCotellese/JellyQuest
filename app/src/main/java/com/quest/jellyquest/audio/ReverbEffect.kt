package com.quest.jellyquest.audio

import android.media.audiofx.EnvironmentalReverb

/**
 * Abstraction over Android's EnvironmentalReverb for testability.
 * Production code uses [AndroidReverbEffect]; tests use a fake.
 */
interface ReverbEffect {
    fun create(audioSessionId: Int)
    fun setEnabled(enabled: Boolean)
    fun applyParameters(params: ReverbParameters)
    fun release()
}

/**
 * Production implementation wrapping Android's EnvironmentalReverb AudioEffect.
 * Attaches to an ExoPlayer audio session for hardware-accelerated room simulation.
 */
class AndroidReverbEffect : ReverbEffect {

    private var reverb: EnvironmentalReverb? = null

    override fun create(audioSessionId: Int) {
        release()
        reverb = EnvironmentalReverb(0, audioSessionId)
    }

    override fun setEnabled(enabled: Boolean) {
        reverb?.enabled = enabled
    }

    override fun applyParameters(params: ReverbParameters) {
        reverb?.apply {
            decayTime = params.decayTimeMs
            roomLevel = params.roomLevel
            roomHFLevel = params.roomHFLevel
            reverbLevel = params.reverbLevel
            reverbDelay = params.reverbDelayMs
            diffusion = params.diffusion
            density = params.density
        }
    }

    override fun release() {
        reverb?.release()
        reverb = null
    }
}
