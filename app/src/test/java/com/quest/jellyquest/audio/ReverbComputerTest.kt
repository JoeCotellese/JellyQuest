package com.quest.jellyquest.audio

import com.quest.jellyquest.RoomGeometry
import com.quest.jellyquest.THEATER_EXPERIENCES
import com.quest.jellyquest.TheaterEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverbComputerTest {

    private val rooms = THEATER_EXPERIENCES.map { TheaterEnvironment.computeRoom(it) }
    private val screeningRoom = rooms[0]
    private val multiplex = rooms[1]
    private val plf = rooms[2]
    private val imax = rooms[3]

    // --- Volume computation ---

    @Test
    fun `room volume increases with theater size`() {
        val volumes = rooms.map { ReverbComputer.roomVolume(it) }
        for (i in 0 until volumes.size - 1) {
            assertTrue(
                "Volume[$i] (${volumes[i]}) should be < Volume[${i + 1}] (${volumes[i + 1]})",
                volumes[i] < volumes[i + 1],
            )
        }
    }

    // --- Decay time ---

    @Test
    fun `decay time increases with room size`() {
        val decays = rooms.map { ReverbComputer.computeReverb(it).decayTimeMs }
        for (i in 0 until decays.size - 1) {
            assertTrue(
                "Decay[$i] (${decays[i]}) should be < Decay[${i + 1}] (${decays[i + 1]})",
                decays[i] < decays[i + 1],
            )
        }
    }

    @Test
    fun `screening room decay time in range 300-500ms`() {
        val params = ReverbComputer.computeReverb(screeningRoom)
        assertTrue("Decay ${params.decayTimeMs}ms too low", params.decayTimeMs >= 300)
        assertTrue("Decay ${params.decayTimeMs}ms too high", params.decayTimeMs <= 500)
    }

    @Test
    fun `IMAX decay time in range 1500-2200ms`() {
        val params = ReverbComputer.computeReverb(imax)
        assertTrue("Decay ${params.decayTimeMs}ms too low", params.decayTimeMs >= 1500)
        assertTrue("Decay ${params.decayTimeMs}ms too high", params.decayTimeMs <= 2200)
    }

    // --- Wet mix cap ---

    @Test
    fun `wet mix never exceeds 35 percent for any preset`() {
        rooms.forEachIndexed { i, room ->
            val params = ReverbComputer.computeReverb(room)
            val wetRatio = params.wetMixRatio()
            assertTrue(
                "Preset $i wet mix ${wetRatio * 100}% exceeds 35%",
                wetRatio <= 0.35f,
            )
        }
    }

    @Test
    fun `wet mix increases with room size`() {
        val wets = rooms.map { ReverbComputer.computeReverb(it).wetMixRatio() }
        for (i in 0 until wets.size - 1) {
            assertTrue(
                "Wet[$i] (${wets[i]}) should be < Wet[${i + 1}] (${wets[i + 1]})",
                wets[i] < wets[i + 1],
            )
        }
    }

    // --- Diffusion and density ---

    @Test
    fun `diffusion is in valid range for all presets`() {
        rooms.forEach { room ->
            val params = ReverbComputer.computeReverb(room)
            assertTrue("Diffusion ${params.diffusion} below 0", params.diffusion >= 0)
            assertTrue("Diffusion ${params.diffusion} above 1000", params.diffusion <= 1000)
        }
    }

    @Test
    fun `density is in valid range for all presets`() {
        rooms.forEach { room ->
            val params = ReverbComputer.computeReverb(room)
            assertTrue("Density ${params.density} below 0", params.density >= 0)
            assertTrue("Density ${params.density} above 1000", params.density <= 1000)
        }
    }

    // --- Interpolation ---

    @Test
    fun `interpolation between screening room and multiplex produces intermediate values`() {
        val smallParams = ReverbComputer.computeReverb(screeningRoom)
        val largeParams = ReverbComputer.computeReverb(multiplex)

        // Create a room with volume halfway between screening room and multiplex
        val midRoom = RoomGeometry(
            widthFront = (screeningRoom.widthFront + multiplex.widthFront) / 2f,
            widthBack = (screeningRoom.widthBack + multiplex.widthBack) / 2f,
            depth = (screeningRoom.depth + multiplex.depth) / 2f,
            ceilingHeight = (screeningRoom.ceilingHeight + multiplex.ceilingHeight) / 2f,
            frontDistance = 15f,
            backDistance = 25f,
        )
        val midParams = ReverbComputer.computeReverb(midRoom)

        assertTrue(
            "Mid decay (${midParams.decayTimeMs}) should be between ${smallParams.decayTimeMs} and ${largeParams.decayTimeMs}",
            midParams.decayTimeMs > smallParams.decayTimeMs && midParams.decayTimeMs < largeParams.decayTimeMs,
        )
    }

    // --- Clamping ---

    @Test
    fun `room smaller than screening room clamps to screening room parameters`() {
        val tinyRoom = RoomGeometry(
            widthFront = 4f, widthBack = 4.5f, depth = 5f,
            ceilingHeight = 3f, frontDistance = 3f, backDistance = 5f,
        )
        val tinyParams = ReverbComputer.computeReverb(tinyRoom)
        val screeningParams = ReverbComputer.computeReverb(screeningRoom)

        assertEquals(screeningParams.decayTimeMs, tinyParams.decayTimeMs)
        assertEquals(screeningParams.reverbLevel, tinyParams.reverbLevel)
    }

    @Test
    fun `room larger than IMAX clamps to max anchor parameters`() {
        val hugeRoom = RoomGeometry(
            widthFront = 50f, widthBack = 60f, depth = 100f,
            ceilingHeight = 30f, frontDistance = 40f, backDistance = 100f,
        )
        val hugeParams = ReverbComputer.computeReverb(hugeRoom)
        val alsoHugeRoom = RoomGeometry(
            widthFront = 100f, widthBack = 120f, depth = 200f,
            ceilingHeight = 50f, frontDistance = 80f, backDistance = 200f,
        )
        val alsoHugeParams = ReverbComputer.computeReverb(alsoHugeRoom)

        // Both should clamp to the same max values
        assertEquals(hugeParams.decayTimeMs, alsoHugeParams.decayTimeMs)
        assertEquals(hugeParams.reverbLevel, alsoHugeParams.reverbLevel)
    }

    // --- Reverb delay ---

    @Test
    fun `reverb delay increases with room size`() {
        val delays = rooms.map { ReverbComputer.computeReverb(it).reverbDelayMs }
        for (i in 0 until delays.size - 1) {
            assertTrue(
                "Delay[$i] (${delays[i]}) should be < Delay[${i + 1}] (${delays[i + 1]})",
                delays[i] < delays[i + 1],
            )
        }
    }
}
