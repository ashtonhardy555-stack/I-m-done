package com.mariocart.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Red = Color(0xFFE50914)
val DarkRed = Color(0xFFB20710)
val Bg = Color(0xFF0A0A0A)
val Bg2 = Color(0xFF141414)
val Bg3 = Color(0xFF1F1F1F)
val TextPrimary = Color(0xFFE5E5E5)
val TextMuted = Color(0xFF999999)
val Gold = Color(0xFFF5C518)

private val DarkScheme = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    secondary = Gold,
    background = Bg,
    surface = Bg2,
    surfaceVariant = Bg3,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Red,
)

@Composable
fun MarioCartTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content
    )
}
