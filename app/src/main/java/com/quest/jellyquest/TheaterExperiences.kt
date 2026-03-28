package com.quest.jellyquest

data class TheaterExperience(
    val name: String,
    val description: String,
    val screenWidthM: Float,
    val screenHeightM: Float,
    val screenBottomM: Float = STAGE_HEIGHT,
    val seats: List<SeatPosition>,
)

data class SeatPosition(
    val label: String,
    val distanceM: Float,
    val riserHeightM: Float = 0f,
)

// Bottom of screen sits at stage height, like a real theater
const val STAGE_HEIGHT = 1.2f

// Riser heights calculated from 7.1° stadium slope (tan(7.1°) ≈ 0.124m rise per meter).
// Front row is the baseline (0m rise); other seats rise relative to front row distance.
val THEATER_EXPERIENCES = listOf(
    TheaterExperience(
        name = "Screening Room",
        description = "Intimate indie cinema — 7m screen",
        screenWidthM = 7.0f,
        screenHeightM = 2.93f,
        seats = listOf(
            SeatPosition("Front", 3.5f, riserHeightM = 0.0f),
            SeatPosition("Middle", 5.0f, riserHeightM = 0.19f),
            SeatPosition("Back", 10.0f, riserHeightM = 0.81f),
        ),
    ),
    TheaterExperience(
        name = "Multiplex",
        description = "Standard moviegoing — 10m screen",
        screenWidthM = 10.0f,
        screenHeightM = 4.18f,
        seats = listOf(
            SeatPosition("Front", 5.0f, riserHeightM = 0.0f),
            SeatPosition("Middle", 10.0f, riserHeightM = 0.62f),
            SeatPosition("Back", 16.0f, riserHeightM = 1.37f),
        ),
    ),
    TheaterExperience(
        name = "Premium Large Format",
        description = "Dolby Cinema / premium — 14m screen",
        screenWidthM = 14.0f,
        screenHeightM = 5.86f,
        seats = listOf(
            SeatPosition("Front", 7.0f, riserHeightM = 0.0f),
            SeatPosition("Middle", 12.0f, riserHeightM = 0.62f),
            SeatPosition("Back", 20.0f, riserHeightM = 1.61f),
        ),
    ),
    TheaterExperience(
        name = "IMAX",
        description = "Wall-to-wall immersion — 22m screen",
        screenWidthM = 22.0f,
        screenHeightM = 12.0f,
        seats = listOf(
            SeatPosition("Front", 10.0f, riserHeightM = 0.0f),
            SeatPosition("Middle", 16.0f, riserHeightM = 0.75f),
            SeatPosition("Back", 25.0f, riserHeightM = 1.86f),
        ),
    ),
)
