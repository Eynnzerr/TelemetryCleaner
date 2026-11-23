@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.eynnzerr.telemetrycleaner.data.preferences.ThemePalette

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = Color(0xFF4B6359),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = Color(0xFF3F6286),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    surface = LightSurface,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFFDDE4DF),
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = Color(0xFFB3CCC0),
    onSecondary = Color(0xFF1E352C),
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = Color(0xFFA7CBEF),
    onTertiary = Color(0xFF0A3354),
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    surface = DarkSurface,
    surfaceContainerLowest = Color(0xFF0C120E),
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF313934),
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
)

/** The original teal light and dark color schemes. */
private val TealSchemes = LightColorScheme to DarkColorScheme

/** Light and dark versions of the blue color palette with a violet tertiary accent. */
private val BlueSchemes = presetSchemes(
    lightPrimary = Color(0xFF345CA6),
    lightContainer = Color(0xFFD9E2FF),
    lightTertiary = Color(0xFF705575),
    lightTertiaryContainer = Color(0xFFFAD8FD),
    darkPrimary = Color(0xFFAEC6FF),
    darkContainer = Color(0xFF19437D),
    darkTertiary = Color(0xFFDDBCE0),
    darkTertiaryContainer = Color(0xFF573E5C),
)

/** Light and dark versions of the purple color palette with a rose-coral tertiary accent. */
private val PurpleSchemes = presetSchemes(
    lightPrimary = Color(0xFF6B4FA2),
    lightContainer = Color(0xFFEBDCFF),
    lightTertiary = Color(0xFF834C64),
    lightTertiaryContainer = Color(0xFFFFD8E6),
    darkPrimary = Color(0xFFD4BBFF),
    darkContainer = Color(0xFF533788),
    darkTertiary = Color(0xFFF6B2CD),
    darkTertiaryContainer = Color(0xFF68354C),
)

/** Light and dark versions of the amber color palette with a sage-green tertiary accent. */
private val AmberSchemes = presetSchemes(
    lightPrimary = Color(0xFF805613),
    lightContainer = Color(0xFFFFDFAD),
    lightTertiary = Color(0xFF47664A),
    lightTertiaryContainer = Color(0xFFC9ECC9),
    darkPrimary = Color(0xFFF3BF69),
    darkContainer = Color(0xFF604000),
    darkTertiary = Color(0xFFADD0AE),
    darkTertiaryContainer = Color(0xFF304E34),
)

/** Light and dark versions of the green palette with a teal-cyan tertiary accent. */
private val GreenSchemes = presetSchemes(
    lightPrimary = Color(0xFF386A20),
    lightContainer = Color(0xFFD4F0B9),
    lightTertiary = Color(0xFF19686B),
    lightTertiaryContainer = Color(0xFFA8EFF2),
    darkPrimary = Color(0xFFA4D789),
    darkContainer = Color(0xFF22520B),
    darkTertiary = Color(0xFF8CD3D6),
    darkTertiaryContainer = Color(0xFF004F52),
)

/** Light and dark versions of the cyan palette with an indigo-blue tertiary accent. */
private val CyanSchemes = presetSchemes(
    lightPrimary = Color(0xFF006878),
    lightContainer = Color(0xFFB2EBF6),
    lightTertiary = Color(0xFF4E5E8A),
    lightTertiaryContainer = Color(0xFFD9E2FF),
    darkPrimary = Color(0xFF7CD4E5),
    darkContainer = Color(0xFF004E5B),
    darkTertiary = Color(0xFFB7C7F9),
    darkTertiaryContainer = Color(0xFF364670),
)

/** Light and dark versions of the rose palette with a warm terracotta tertiary accent. */
private val RoseSchemes = presetSchemes(
    lightPrimary = Color(0xFF9B3F63),
    lightContainer = Color(0xFFFFD8E6),
    lightTertiary = Color(0xFF7F5634),
    lightTertiaryContainer = Color(0xFFFFDCC2),
    darkPrimary = Color(0xFFFFB0CB),
    darkContainer = Color(0xFF7C2849),
    darkTertiary = Color(0xFFF2BC93),
    darkTertiaryContainer = Color(0xFF633F20),
)

/** Light and dark versions of the orange palette with an olive-amber tertiary accent. */
private val OrangeSchemes = presetSchemes(
    lightPrimary = Color(0xFF994612),
    lightContainer = Color(0xFFFFDCC7),
    lightTertiary = Color(0xFF65602B),
    lightTertiaryContainer = Color(0xFFECE5A3),
    darkPrimary = Color(0xFFFFB68B),
    darkContainer = Color(0xFF793100),
    darkTertiary = Color(0xFFD0C989),
    darkTertiaryContainer = Color(0xFF4D4816),
)

/** Light and dark versions of the slate palette with a lavender tertiary accent. */
private val SlateSchemes = presetSchemes(
    lightPrimary = Color(0xFF485E7B),
    lightContainer = Color(0xFFD3E4FF),
    lightTertiary = Color(0xFF6A5779),
    lightTertiaryContainer = Color(0xFFF1DAFF),
    darkPrimary = Color(0xFFB0C8E8),
    darkContainer = Color(0xFF304662),
    darkTertiary = Color(0xFFD6BEE5),
    darkTertiaryContainer = Color(0xFF523F60),
)

/** Applies Material 3 Expressive spring motion, typography, and color roles. */
@Composable
fun TelemetryCleanerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    palette: ThemePalette = ThemePalette.SYSTEM,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = resolveColorScheme(palette, darkTheme, dynamicColor),
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = content,
    )
}

/** Returns primary, primaryContainer, and tertiaryContainer for tri-tone palette swatches. */
@Composable
internal fun ThemePalette.previewColors(): Triple<Color, Color, Color> {
    val scheme = resolveColorScheme(this, isSystemInDarkTheme(), dynamicColor = true)
    return Triple(scheme.primary, scheme.primaryContainer, scheme.tertiaryContainer)
}

/** Resolves system dynamic colors when available, or chooses the light/dark preset. */
@Composable
private fun resolveColorScheme(
    palette: ThemePalette,
    darkTheme: Boolean,
    dynamicColor: Boolean,
): ColorScheme {
    if (palette == ThemePalette.SYSTEM && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    val (light, dark) = when (palette) {
        ThemePalette.SYSTEM, ThemePalette.TEAL -> TealSchemes
        ThemePalette.BLUE -> BlueSchemes
        ThemePalette.PURPLE -> PurpleSchemes
        ThemePalette.AMBER -> AmberSchemes
        ThemePalette.GREEN -> GreenSchemes
        ThemePalette.CYAN -> CyanSchemes
        ThemePalette.ROSE -> RoseSchemes
        ThemePalette.ORANGE -> OrangeSchemes
        ThemePalette.SLATE -> SlateSchemes
    }
    return if (darkTheme) dark else light
}

/** Builds both appearances for a preset from primary and complementary tertiary roles. */
private fun presetSchemes(
    lightPrimary: Color,
    lightContainer: Color,
    lightTertiary: Color,
    lightTertiaryContainer: Color,
    darkPrimary: Color,
    darkContainer: Color,
    darkTertiary: Color,
    darkTertiaryContainer: Color,
): Pair<ColorScheme, ColorScheme> =
    presetScheme(
        primary = lightPrimary,
        primaryContainer = lightContainer,
        inversePrimary = darkPrimary,
        tertiary = lightTertiary,
        tertiaryContainer = lightTertiaryContainer,
        darkTheme = false,
    ) to presetScheme(
        primary = darkPrimary,
        primaryContainer = darkContainer,
        inversePrimary = lightPrimary,
        tertiary = darkTertiary,
        tertiaryContainer = darkTertiaryContainer,
        darkTheme = true,
    )

/** Constructs a full Material 3 tonal surface and accent color scheme for a preset palette. */
private fun presetScheme(
    primary: Color,
    primaryContainer: Color,
    inversePrimary: Color,
    tertiary: Color,
    tertiaryContainer: Color,
    darkTheme: Boolean,
): ColorScheme {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val foreground = if (darkTheme) Color(0xFFE6E6EA) else Color(0xFF191B1F)
    val neutral = if (darkTheme) Color(0xFF111216) else Color(0xFFFFFFFF)
    val surface = lerp(neutral, primary, if (darkTheme) 0.02f else 0.028f)
    val secondary = lerp(primary, foreground, 0.32f)
    val secondaryContainer = lerp(surface, primaryContainer, 0.62f)
    val onAccent = if (darkTheme) lerp(Color.Black, primary, 0.18f) else Color.White
    val onTertiary = if (darkTheme) lerp(Color.Black, tertiary, 0.18f) else Color.White
    val onPrimaryContainer = lerp(foreground, primary, 0.22f)
    val onTertiaryContainer = lerp(foreground, tertiary, 0.22f)

    return base.copy(
        primary = primary,
        onPrimary = onAccent,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onAccent,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = foreground,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = surface,
        onBackground = foreground,
        surface = surface,
        onSurface = foreground,
        onSurfaceVariant = lerp(foreground, surface, 0.28f),
        surfaceVariant = lerp(surface, foreground, 0.12f),
        surfaceTint = primary,
        surfaceContainerLowest = if (darkTheme) Color(0xFF0C0D10) else Color.White,
        surfaceContainerLow = lerp(surface, foreground, if (darkTheme) 0.045f else 0.032f),
        surfaceContainer = lerp(surface, foreground, if (darkTheme) 0.075f else 0.055f),
        surfaceContainerHigh = lerp(surface, foreground, if (darkTheme) 0.105f else 0.08f),
        surfaceContainerHighest = lerp(surface, foreground, if (darkTheme) 0.14f else 0.105f),
        outline = lerp(foreground, surface, 0.52f),
        outlineVariant = lerp(foreground, surface, if (darkTheme) 0.78f else 0.84f),
    )
}
