package com.quest.jellyquest

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.meta.spatial.uiset.theme.SpatialColorScheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme

val DraculaBackground = Color(0xFF282A36)
val DraculaCurrentLine = Color(0xFF44475A)
val DraculaForeground = Color(0xFFF8F8F2)
val DraculaComment = Color(0xFF6272A4)
val DraculaCyan = Color(0xFF8BE9FD)
val DraculaGreen = Color(0xFF50FA7B)
val DraculaOrange = Color(0xFFFFB86C)
val DraculaPink = Color(0xFFFF79C6)
val DraculaPurple = Color(0xFFBD93F9)
val DraculaRed = Color(0xFFFF5555)
val DraculaYellow = Color(0xFFF1FA8C)

fun draculaSpatialColorScheme(): SpatialColorScheme {
    return darkSpatialColorScheme().copy(
        panel = SolidColor(DraculaBackground),
        dialog = SolidColor(DraculaBackground),
        menu = DraculaCurrentLine,
        sideNavBackground = DraculaBackground,
        primaryButton = DraculaPurple,
        secondaryButton = DraculaCurrentLine,
        negativeButton = DraculaRed,
        controlButton = DraculaPink,
        primaryAlphaBackground = DraculaForeground,
        secondaryAlphaBackground = DraculaForeground.copy(alpha = 0.7f),
        active = DraculaGreen,
        positive = DraculaGreen,
        negative = DraculaRed,
        hover = DraculaCurrentLine,
        pressed = DraculaComment
    )
}
