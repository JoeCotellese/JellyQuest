package com.quest.jellyquest.audio

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSettingsTest {

    private val store = mutableMapOf<String, Any>()

    private val editor = mockk<SharedPreferences.Editor>(relaxed = true).apply {
        every { putBoolean(any(), any()) } answers {
            store[firstArg()] = secondArg<Boolean>()
            this@apply
        }
    }

    private val prefs = mockk<SharedPreferences>().apply {
        every { getBoolean(any(), any()) } answers {
            store[firstArg()] as? Boolean ?: secondArg()
        }
        every { edit() } returns editor
    }

    @Test
    fun `spatial audio defaults to true`() {
        val settings = AudioSettings(prefs)
        assertTrue(settings.spatialAudioEnabled)
    }

    @Test
    fun `room acoustics defaults to true`() {
        val settings = AudioSettings(prefs)
        assertTrue(settings.roomAcousticsEnabled)
    }

    @Test
    fun `setSpatialAudio persists value`() {
        val settings = AudioSettings(prefs)
        settings.spatialAudioEnabled = false

        // Reading back from same prefs should return false
        assertFalse(settings.spatialAudioEnabled)
        verify { editor.putBoolean(AudioSettings.KEY_SPATIAL_AUDIO, false) }
    }

    @Test
    fun `setRoomAcoustics persists value`() {
        val settings = AudioSettings(prefs)
        settings.roomAcousticsEnabled = false

        assertFalse(settings.roomAcousticsEnabled)
        verify { editor.putBoolean(AudioSettings.KEY_ROOM_ACOUSTICS, false) }
    }

    @Test
    fun `toggles are independent`() {
        val settings = AudioSettings(prefs)
        settings.spatialAudioEnabled = false

        // Room acoustics should still be true (default)
        assertTrue(settings.roomAcousticsEnabled)
        assertFalse(settings.spatialAudioEnabled)
    }
}
