package dev.clapctrl.app.ui

import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF111413)
val Surface = Color(0xFF1A1E1C)
val SurfaceRaised = Color(0xFF222724)
val Border = Color(0xFF303832)
val Accent = Color(0xFF9FE2B1)
val Muted = Color(0xFF9DAAA1)
val Ink = Color(0xFFE7EDE8)
val Danger = Color(0xFFF1A899)

@Composable fun ClapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = darkColors(primary = Accent, primaryVariant = Accent, secondary = Accent,
            background = Background, surface = Surface, onPrimary = Background,
            onSecondary = Background, onSurface = Ink, onBackground = Ink, error = Danger),
        content = { CompositionLocalProvider(LocalContentColor provides Ink, content = content) },
    )
}
