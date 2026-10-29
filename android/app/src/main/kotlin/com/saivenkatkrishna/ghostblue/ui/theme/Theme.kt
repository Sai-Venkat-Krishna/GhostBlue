package com.saivenkatkrishna.ghostblue.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.saivenkatkrishna.ghostblue.settings.AppTheme

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
 * The iOS app's Rivian theme (`RVTokens`): warm near-black or paper
 * surfaces, solid ink for active commands and a single amber-gold accent,
 * mapped onto Material roles so every component picks it up.
 */
private object RivianTokens {
    val gold = Color(0xFFFFAC00)
    val goldSoft = Color(0xFFFFC747)
}

private val RivianLight = lightColorScheme(
    primary = Color(0xFF111113),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E6E3),
    onPrimaryContainer = Color(0xFF111113),
    secondary = Color(0xFF6B6B72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBEBE8),
    onSecondaryContainer = Color(0xFF111113),
    tertiary = Color(0xFF8A5D00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = RivianTokens.goldSoft,
    onTertiaryContainer = Color(0xFF2B1B00),
    background = Color(0xFFF4F4F2),
    onBackground = Color(0xFF111113),
    surface = Color(0xFFF4F4F2),
    onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFEBEBE8),
    onSurfaceVariant = Color(0xFF6B6B72),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEBEBE8),
    surfaceContainerHighest = Color(0xFFE6E6E3),
    outline = Color(0xFFB5B5B0),
    outlineVariant = Color(0x14000000),
    error = Color(0xFFD93A3A),
)

private val RivianDark = darkColorScheme(
    primary = Color(0xFFF2F2F0),
    onPrimary = Color(0xFF111113),
    primaryContainer = Color(0xFF2A2A2D),
    onPrimaryContainer = Color(0xFFF2F2F0),
    secondary = Color(0xFF9C9CA3),
    onSecondary = Color(0xFF111113),
    secondaryContainer = Color(0xFF232326),
    onSecondaryContainer = Color(0xFFF2F2F0),
    tertiary = RivianTokens.gold,
    onTertiary = Color(0xFF231600),
    tertiaryContainer = Color(0xFF3A2A08),
    onTertiaryContainer = RivianTokens.goldSoft,
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFF2F2F0),
    surface = Color(0xFF0B0B0C),
    onSurface = Color(0xFFF2F2F0),
    surfaceVariant = Color(0xFF232326),
    onSurfaceVariant = Color(0xFF9C9CA3),
    surfaceContainerLowest = Color(0xFF0B0B0C),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF18181A),
    surfaceContainerHigh = Color(0xFF232326),
    surfaceContainerHighest = Color(0xFF2A2A2D),
    outline = Color(0xFF636366),
    outlineVariant = Color(0x1AFFFFFF),
    error = Color(0xFFFF5A5A),
)

/**
 * Colours that mean something about the car, beside the Material roles:
 * Rivian groups features by colour (climate blue, energy green and so on);
 * the Material theme takes them from its own palette.
 */
@Immutable
data class GhostBlueColors(
    val climate: Color,
    val energy: Color,
    val location: Color,
    val danger: Color,
    /** Warnings and service: amber. */
    val service: Color,
    val accent: Color,
    val isRivian: Boolean,
)

val LocalGhostBlueColors = staticCompositionLocalOf {
    GhostBlueColors(
        climate = Color(0xFF3D7BFF),
        energy = Color(0xFF34C468),
        location = Color(0xFF8069E6),
        danger = Color(0xFFFF5A5A),
        service = Color(0xFFF08C2B),
        accent = Color(0xFF2456C6),
        isRivian = false,
    )
}

private fun ghostBlueColors(theme: AppTheme, scheme: ColorScheme, dark: Boolean) = when (theme) {
    AppTheme.RIVIAN -> GhostBlueColors(
        climate = Color(if (dark) 0xFF3D7BFF else 0xFF2A6BED),
        energy = Color(if (dark) 0xFF34C468 else 0xFF2EB35C),
        location = Color(if (dark) 0xFF8069E6 else 0xFF6E57D1),
        danger = Color(if (dark) 0xFFFF5A5A else 0xFFD93A3A),
        service = Color(if (dark) 0xFFF08C2B else 0xFFE07B1A),
        accent = if (dark) RivianTokens.gold else Color(0xFF8A5D00),
        isRivian = true,
    )
    AppTheme.MATERIAL -> GhostBlueColors(
        climate = scheme.primary,
        energy = if (dark) Color(0xFF6DD58C) else Color(0xFF1B6D3A),
        location = scheme.tertiary,
        danger = scheme.error,
        service = if (dark) Color(0xFFFFB870) else Color(0xFF9A5200),
        accent = scheme.primary,
        isRivian = false,
    )
}

/**
 * Material 3 Expressive: wallpaper-based dynamic colour on Android 12+,
 * expressive motion (springier, with overshoot) and the expressive shapes
 * and type the components pick up. [AppTheme.RIVIAN] swaps in the iOS app's
 * Rivian palette and its calmer, standard motion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GhostBlueTheme(
    theme: AppTheme = AppTheme.MATERIAL,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        theme == AppTheme.RIVIAN -> if (darkTheme) RivianDark else RivianLight
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalGhostBlueColors provides ghostBlueColors(theme, colorScheme, darkTheme)) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = if (theme == AppTheme.RIVIAN) MotionScheme.standard() else MotionScheme.expressive(),
            content = content,
        )
    }
}
