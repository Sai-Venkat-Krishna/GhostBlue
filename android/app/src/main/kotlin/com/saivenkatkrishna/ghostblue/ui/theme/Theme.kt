package com.saivenkatkrishna.ghostblue.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette (before Android 12, or with dynamic colour off), built
// around GhostBlue's blue.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2456C6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001848),
    secondary = Color(0xFF575E71),
    secondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFF00696E),
    tertiaryContainer = Color(0xFF9CF0F6),
    surface = Color(0xFFFAF8FF),
    surfaceContainer = Color(0xFFEEEDF4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF002B74),
    primaryContainer = Color(0xFF003FA3),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFC0C6DC),
    secondaryContainer = Color(0xFF404659),
    tertiary = Color(0xFF80D4DA),
    tertiaryContainer = Color(0xFF004F53),
    surface = Color(0xFF121318),
    surfaceContainer = Color(0xFF1E1F25),
)

/**
 * Material 3 Expressive: wallpaper-based dynamic colour on Android 12+,
 * expressive motion (springier, with overshoot) and the expressive shapes
 * and type the components pick up.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GhostBlueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
