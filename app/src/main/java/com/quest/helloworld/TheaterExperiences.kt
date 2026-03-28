package com.quest.helloworld

data class TheaterExperience(
    val name: String,
    val description: String,
    val screenSizeIndex: Int,
    val screenHeightM: Float,
    val seats: List<SeatPosition>,
)

data class SeatPosition(
    val label: String,
    val distanceIndex: Int,
)

// Screen center at 2.5m simulates a raised theater screen viewed from a seated position (~1.1m eye height)
const val THEATER_SCREEN_HEIGHT = 2.5f

val THEATER_EXPERIENCES = listOf(
    TheaterExperience(
        name = "Screening Room",
        description = "Intimate indie cinema",
        screenSizeIndex = 5,  // Small Theater (7m)
        screenHeightM = THEATER_SCREEN_HEIGHT,
        seats = listOf(
            SeatPosition("Front", 2),   // Home Theater 4m
            SeatPosition("Middle", 3),  // Front Row 5m
            SeatPosition("Back", 5),    // Back Row 20m
        ),
    ),
    TheaterExperience(
        name = "Multiplex",
        description = "Standard moviegoing",
        screenSizeIndex = 6,  // Mid Theater (10m)
        screenHeightM = THEATER_SCREEN_HEIGHT,
        seats = listOf(
            SeatPosition("Front", 3),   // Front Row 5m
            SeatPosition("Middle", 4),  // Mid Theater 12m
            SeatPosition("Back", 5),    // Back Row 20m
        ),
    ),
    TheaterExperience(
        name = "Premium Large Format",
        description = "Dolby Cinema / premium",
        screenSizeIndex = 7,  // Large Theater (14m)
        screenHeightM = THEATER_SCREEN_HEIGHT,
        seats = listOf(
            SeatPosition("Front", 3),   // Front Row 5m
            SeatPosition("Middle", 4),  // Mid Theater 12m
            SeatPosition("Back", 5),    // Back Row 20m
        ),
    ),
    TheaterExperience(
        name = "IMAX",
        description = "Wall-to-wall immersion",
        screenSizeIndex = 9,  // IMAX (22m)
        screenHeightM = THEATER_SCREEN_HEIGHT,
        seats = listOf(
            SeatPosition("Front", 4),   // Mid Theater 12m
            SeatPosition("Middle", 5),  // Back Row 20m
            SeatPosition("Back", 6),    // Balcony 30m
        ),
    ),
)
