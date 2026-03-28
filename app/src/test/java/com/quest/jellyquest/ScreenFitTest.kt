package com.quest.jellyquest

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenFitTest {

    // --- Aspect ratio matches screen bounds ---

    @Test
    fun `video matching screen aspect ratio uses full bounds`() {
        // 2.39:1 video in 2.39:1 screen (14m x 5.86m)
        val (w, h) = fitVideoToScreen(
            screenWidthM = 14.0f, screenHeightM = 5.86f,
            videoWidth = 2560, videoHeight = 1072,
        )
        assertEquals(14.0f, w, 0.01f)
        assertEquals(5.86f, h, 0.01f)
    }

    // --- Video narrower than screen (height-limited) ---

    @Test
    fun `16x9 video in wide screen uses full height and narrower width`() {
        // 16:9 (1.78:1) video in 2.39:1 screen
        val (w, h) = fitVideoToScreen(
            screenWidthM = 14.0f, screenHeightM = 5.86f,
            videoWidth = 1920, videoHeight = 1080,
        )
        // Height-limited: full height 5.86m, width = 5.86 * (1920/1080) = 10.42m
        assertEquals(10.42f, w, 0.01f)
        assertEquals(5.86f, h, 0.01f)
    }

    // --- Video wider than screen (width-limited) ---

    @Test
    fun `ultra-wide video in 16x9 screen uses full width and shorter height`() {
        // 2.39:1 video in a 16:9 screen (3.56m x 2.0m)
        val (w, h) = fitVideoToScreen(
            screenWidthM = 3.56f, screenHeightM = 2.0f,
            videoWidth = 2560, videoHeight = 1072,
        )
        // Width-limited: full width 3.56m, height = 3.56 / (2560/1072) = 1.49m
        assertEquals(3.56f, w, 0.01f)
        assertEquals(1.49f, h, 0.01f)
    }

    // --- Square video ---

    @Test
    fun `square video in wide screen uses full height`() {
        val (w, h) = fitVideoToScreen(
            screenWidthM = 14.0f, screenHeightM = 5.86f,
            videoWidth = 1080, videoHeight = 1080,
        )
        assertEquals(5.86f, w, 0.01f)
        assertEquals(5.86f, h, 0.01f)
    }

    // --- IMAX tall format ---

    @Test
    fun `IMAX 1_43 video in wide screen uses full height`() {
        // IMAX 1.43:1 in 2.39:1 screen
        val (w, h) = fitVideoToScreen(
            screenWidthM = 14.0f, screenHeightM = 5.86f,
            videoWidth = 1430, videoHeight = 1000,
        )
        // Height-limited: full height, width = 5.86 * 1.43 = 8.38m
        assertEquals(8.38f, w, 0.01f)
        assertEquals(5.86f, h, 0.01f)
    }

    // --- Edge cases ---

    @Test
    fun `zero video dimensions returns screen bounds`() {
        val (w, h) = fitVideoToScreen(
            screenWidthM = 14.0f, screenHeightM = 5.86f,
            videoWidth = 0, videoHeight = 0,
        )
        assertEquals(14.0f, w, 0.01f)
        assertEquals(5.86f, h, 0.01f)
    }

    // --- Vertical centering ---

    @Test
    fun `fitted screen center Y accounts for reduced height`() {
        // 16:9 in PLF: height shrinks from 5.86m to 10.42/1.78 ≈ doesn't apply
        // Width-limited case: 2.39:1 in 16:9 screen, height = 1.49m
        val (_, h) = fitVideoToScreen(
            screenWidthM = 3.56f, screenHeightM = 2.0f,
            videoWidth = 2560, videoHeight = 1072,
        )
        // Screen bottom at 1.2m (STAGE_HEIGHT), center = 1.2 + 1.49/2 = 1.945
        val screenBottom = 1.2f
        val centerY = screenBottom + h / 2f
        assertEquals(1.945f, centerY, 0.01f)
    }
}
