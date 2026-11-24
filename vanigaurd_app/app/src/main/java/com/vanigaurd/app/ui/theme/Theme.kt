
package com.vanigaurd.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF20C997),
    onPrimary = Color(0xFF06251D),
    secondary = Color(0xFF75E0BD),
    onSecondary = Color(0xFF082A21),
    tertiary = Color(0xFF8DBFAD),
    background = Color(0xFF080D12),
    onBackground = Color(0xFFEAF2EF),
    surface = Color(0xFF111A20),
    onSurface = Color(0xFFEAF2EF),
    surfaceVariant = Color(0xFF1B292F),
    onSurfaceVariant = Color(0xFFA5B5B1)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF087F5B),
    onPrimary = Color.White,
    secondary = Color(0xFF167A60),
    onSecondary = Color.White,
    tertiary = Color(0xFF397D69),
    background = Color(0xFFF4F7F5),
    onBackground = Color(0xFF14221D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14221D),
    surfaceVariant = Color(0xFFE8F0EC),
    onSurfaceVariant = Color(0xFF53645D)
)

@Composable
fun VaniGaurdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
