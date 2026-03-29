package com.quest.jellyquest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TheaterEnvironmentTest {

    // --- Room geometry for each preset ---

    @Test
    fun `screening room geometry has correct dimensions`() {
        val experience = THEATER_EXPERIENCES[0] // Screening Room: 7m screen, seats 6.5-18m
        val room = TheaterEnvironment.computeRoom(experience)

        assertEquals(11.0f, room.widthFront, 0.01f) // 7 + 4
        assertTrue(room.widthBack > room.widthFront) // angled walls
        assertEquals(23.0f, room.depth, 0.01f) // 18 + 5
        assertEquals(6.5f, room.ceilingHeight, 0.01f)
    }

    @Test
    fun `multiplex geometry has correct dimensions`() {
        val experience = THEATER_EXPERIENCES[1] // Multiplex: 12m screen, seats 11.42-18.91m
        val room = TheaterEnvironment.computeRoom(experience)

        assertEquals(16.0f, room.widthFront, 0.01f) // 12 + 4
        assertEquals(23.91f, room.depth, 0.01f) // 18.91 + 5
        assertEquals(10.0f, room.ceilingHeight, 0.01f)
    }

    @Test
    fun `PLF geometry has correct dimensions`() {
        val experience = THEATER_EXPERIENCES[2] // PLF: 16m screen, seats 14-32m
        val room = TheaterEnvironment.computeRoom(experience)

        assertEquals(20.0f, room.widthFront, 0.01f) // 16 + 4
        assertEquals(37.0f, room.depth, 0.01f) // 32 + 5
        assertEquals(12.0f, room.ceilingHeight, 0.01f)
    }

    @Test
    fun `IMAX geometry has correct dimensions`() {
        val experience = THEATER_EXPERIENCES[3] // IMAX: 22m screen, seats 19-38m
        val room = TheaterEnvironment.computeRoom(experience)

        assertEquals(26.0f, room.widthFront, 0.01f) // 22 + 4
        assertEquals(43.0f, room.depth, 0.01f) // 38 + 5
        assertEquals(18.0f, room.ceilingHeight, 0.01f)
    }

    // --- Wall angle ---

    @Test
    fun `back wall is wider than front for all presets`() {
        THEATER_EXPERIENCES.forEach { experience ->
            val room = TheaterEnvironment.computeRoom(experience)
            assertTrue(
                "${experience.name}: widthBack (${room.widthBack}) should be > widthFront (${room.widthFront})",
                room.widthBack > room.widthFront,
            )
        }
    }

    // --- Ceiling above screen ---

    @Test
    fun `ceiling is above screen top for all presets`() {
        THEATER_EXPERIENCES.forEach { experience ->
            val room = TheaterEnvironment.computeRoom(experience)
            val screenTop = experience.screenBottomM + experience.screenHeightM
            assertTrue(
                "${experience.name}: ceiling (${room.ceilingHeight}) should be > screen top ($screenTop)",
                room.ceilingHeight > screenTop,
            )
        }
    }

    // --- Back wall behind furthest seat ---

    @Test
    fun `back wall is behind furthest seat for all presets`() {
        THEATER_EXPERIENCES.forEach { experience ->
            val room = TheaterEnvironment.computeRoom(experience)
            val furthestSeat = experience.seats.maxOf { it.distanceM }
            assertTrue(
                "${experience.name}: backDistance (${room.backDistance}) should be > furthest seat ($furthestSeat)",
                room.backDistance > furthestSeat,
            )
        }
    }

    // --- Front row minimum viewing distance ---

    @Test
    fun `front row distance is at least 0_87x screen width for all presets`() {
        THEATER_EXPERIENCES.forEach { experience ->
            val frontSeat = experience.seats.minByOrNull { it.distanceM }!!
            val minDistance = experience.screenWidthM * 0.87f
            assertTrue(
                "${experience.name}: front row (${frontSeat.distanceM}m) should be >= 0.87 × screen width ($minDistance m)",
                frontSeat.distanceM >= minDistance,
            )
        }
    }

    // --- Ceiling height from preset ---

    @Test
    fun `ceiling height matches preset value for all presets`() {
        THEATER_EXPERIENCES.forEach { experience ->
            val room = TheaterEnvironment.computeRoom(experience)
            assertEquals(
                "${experience.name}: ceiling should match preset",
                experience.ceilingHeightM,
                room.ceilingHeight,
                0.01f,
            )
        }
    }

    // --- Front distance ---

    @Test
    fun `front distance is at screen wall position`() {
        val experience = THEATER_EXPERIENCES[2] // PLF
        val room = TheaterEnvironment.computeRoom(experience)
        // Screen wall is 1m past the screen (screen sits slightly in front of wall)
        val closestSeat = experience.seats.minOf { it.distanceM }
        assertTrue(room.frontDistance >= closestSeat)
    }

    // --- Screen frame ---

    @Test
    fun `screen frame returns 4 boxes`() {
        val boxes = TheaterEnvironment.screenFrameBoxes(14.0f, 5.86f)
        assertEquals(4, boxes.size)
    }

    @Test
    fun `screen frame boxes surround the screen area`() {
        val screenW = 10.0f
        val screenH = 4.0f
        val boxes = TheaterEnvironment.screenFrameBoxes(screenW, screenH)
        val halfW = screenW / 2f
        val halfH = screenH / 2f
        val border = TheaterEnvironment.FRAME_BORDER

        // Top bar should be above screen half-height
        val topBar = boxes[0]
        assertTrue(topBar.max.y > halfH)

        // Bottom bar should be below negative screen half-height
        val bottomBar = boxes[1]
        assertTrue(bottomBar.min.y < -halfH)

        // Left bar should extend left of screen
        val leftBar = boxes[2]
        assertTrue(leftBar.min.x < -halfW)

        // Right bar should extend right of screen
        val rightBar = boxes[3]
        assertTrue(rightBar.max.x > halfW)
    }
}
