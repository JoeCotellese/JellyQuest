package com.quest.jellyquest.audio

import com.quest.jellyquest.RoomGeometry
import com.quest.jellyquest.THEATER_EXPERIENCES
import com.quest.jellyquest.TheaterEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAcousticsControllerTest {

    private val screeningRoom = TheaterEnvironment.computeRoom(THEATER_EXPERIENCES[0])
    private val imax = TheaterEnvironment.computeRoom(THEATER_EXPERIENCES[3])

    /** Fake that records all interactions for verification. */
    class FakeReverbEffect : ReverbEffect {
        var created = false
        var wasEnabled = false
        var released = false
        var lastParams: ReverbParameters? = null
        var audioSessionId: Int? = null

        override fun create(audioSessionId: Int) {
            this.audioSessionId = audioSessionId
            created = true
            released = false
        }

        override fun setEnabled(enabled: Boolean) {
            wasEnabled = enabled
        }

        override fun applyParameters(params: ReverbParameters) {
            lastParams = params
        }

        override fun release() {
            released = true
            wasEnabled = false
            created = false
        }
    }

    @Test
    fun `enable creates and enables the reverb effect`() {
        val fake = FakeReverbEffect()
        val controller = RoomAcousticsController(fake)

        controller.enable(42)

        assertTrue(fake.created)
        assertTrue(fake.wasEnabled)
        assertEquals(42, fake.audioSessionId)
    }

    @Test
    fun `disable releases the reverb effect`() {
        val fake = FakeReverbEffect()
        val controller = RoomAcousticsController(fake)

        controller.enable(42)
        controller.disable()

        assertTrue(fake.released)
        assertFalse(fake.wasEnabled)
    }

    @Test
    fun `disable without enable is safe`() {
        val fake = FakeReverbEffect()
        val controller = RoomAcousticsController(fake)

        // Should not throw
        controller.disable()
        assertFalse(fake.released)
    }

    @Test
    fun `applyRoom sets parameters matching ReverbComputer output`() = runTest {
        val fake = FakeReverbEffect()
        val controller = RoomAcousticsController(fake)
        controller.enable(1)

        controller.applyRoom(screeningRoom, this)
        // Advance past cross-fade duration
        advanceTimeBy(600)

        val expected = ReverbComputer.computeReverb(screeningRoom)
        assertNotNull(fake.lastParams)
        assertEquals(expected.decayTimeMs, fake.lastParams!!.decayTimeMs)
        assertEquals(expected.reverbLevel, fake.lastParams!!.reverbLevel)
    }

    @Test
    fun `applyRoom cross-fades over 500ms`() = runTest {
        val fake = FakeReverbEffect()
        val controller = RoomAcousticsController(fake)
        controller.enable(1)

        // Set initial room
        controller.applyRoom(screeningRoom, this)
        advanceTimeBy(600)
        val initialDecay = fake.lastParams!!.decayTimeMs

        // Switch to IMAX — should cross-fade
        controller.applyRoom(imax, this)

        // At t=0 of the new fade, params should still be close to initial
        advanceTimeBy(20)
        val earlyDecay = fake.lastParams!!.decayTimeMs

        // Advance to end of cross-fade
        advanceTimeBy(600)
        val finalDecay = fake.lastParams!!.decayTimeMs

        val expectedFinal = ReverbComputer.computeReverb(imax)
        assertTrue(
            "Early decay ($earlyDecay) should be between initial ($initialDecay) and final ($finalDecay)",
            earlyDecay > initialDecay && earlyDecay < finalDecay,
        )
        assertEquals(expectedFinal.decayTimeMs, finalDecay)
    }

    @Test
    fun `applyRoom without enable does nothing`() = runTest {
        val fake = FakeReverbEffect()
        val controller = RoomAcousticsController(fake)

        controller.applyRoom(screeningRoom, this)
        advanceTimeBy(600)

        assertNull(fake.lastParams)
    }
}
