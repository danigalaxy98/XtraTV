package com.github.andreyasadchy.xtra.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object TvColors {
    val Background = Color(0xFF0E0E10)
    val Surface = Color(0xFF1F1F23)
    val SurfaceFocused = Color(0xFF2E2E35)
    val Accent = Color(0xFF9146FF)
    val Text = Color(0xFFEFEFF1)
    val TextSecondary = Color(0xFFADADB8)
    val Live = Color(0xFFEB0400)
}

@Composable
fun XtraTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TvColors.Accent,
            background = TvColors.Background,
            surface = TvColors.Surface,
            onSurface = TvColors.Text,
            onBackground = TvColors.Text,
        ),
        content = content,
    )
}

/**
 * Shared look of every focusable element: dark surface, purple border and a small zoom when focused.
 */
object TvFocus {
    private val cardShape = RoundedCornerShape(12.dp)
    private val pillShape = RoundedCornerShape(50)

    @Composable
    fun cardShape() = ClickableSurfaceDefaults.shape(shape = cardShape)

    @Composable
    fun pillShape() = ClickableSurfaceDefaults.shape(shape = pillShape)

    @Composable
    fun colors(transparent: Boolean = false) = ClickableSurfaceDefaults.colors(
        containerColor = if (transparent) Color.Transparent else TvColors.Surface,
        contentColor = TvColors.Text,
        focusedContainerColor = TvColors.SurfaceFocused,
        focusedContentColor = Color.White,
        pressedContainerColor = TvColors.SurfaceFocused,
        pressedContentColor = Color.White,
    )

    @Composable
    fun border(rounded: Boolean = false) = ClickableSurfaceDefaults.border(
        focusedBorder = Border(
            border = BorderStroke(3.dp, TvColors.Accent),
            shape = if (rounded) pillShape else cardShape,
        ),
    )

    @Composable
    fun scale(focused: Float = 1.05f) = ClickableSurfaceDefaults.scale(focusedScale = focused)
}
