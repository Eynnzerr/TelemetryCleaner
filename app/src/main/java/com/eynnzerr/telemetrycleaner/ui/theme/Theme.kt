@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.eynnzerr.telemetrycleaner.data.ThemePalette

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    surface = LightSurface,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    surface = DarkSurface,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
)

/** The original teal light and dark color schemes. */
private val TealSchemes = LightColorScheme to DarkColorScheme

/** Light and dark versions of the blue color palette. */
private val BlueSchemes = presetSchemes(
    lightPrimary = Color(0xFF345CA6),
    lightContainer = Color(0xFFD9E2FF),
    darkPrimary = Color(0xFFAEC6FF),
    darkContainer = Color(0xFF19437D),
)

/** Light and dark versions of the purple color palette. */
private val PurpleSchemes = presetSchemes(
    lightPrimary = Color(0xFF7353A4),
    lightContainer = Color(0xFFEDDBFF),
    darkPrimary = Color(0xFFD8BAFF),
    darkContainer = Color(0xFF553987),
)

/** Light and dark versions of the amber color palette. */
private val AmberSchemes = presetSchemes(
    lightPrimary = Color(0xFF805613),
    lightContainer = Color(0xFFFFDFAD),
    darkPrimary = Color(0xFFF3BF69),
    darkContainer = Color(0xFF604000),
)

/** Light and dark versions of the green palette. */
private val GreenSchemes = presetSchemes(
    lightPrimary = Color(0xFF386A20),
    lightContainer = Color(0xFFD4F0B9),
    darkPrimary = Color(0xFFA4D789),
    darkContainer = Color(0xFF22520B),
)

/** Light and dark versions of the cyan palette. */
private val CyanSchemes = presetSchemes(
    lightPrimary = Color(0xFF006878),
    lightContainer = Color(0xFFB2EBF6),
    darkPrimary = Color(0xFF7CD4E5),
    darkContainer = Color(0xFF004E5B),
)

/** Light and dark versions of the rose palette. */
private val RoseSchemes = presetSchemes(
    lightPrimary = Color(0xFF9B3F63),
    lightContainer = Color(0xFFFFD8E6),
    darkPrimary = Color(0xFFFFB0CB),
    darkContainer = Color(0xFF7C2849),
)

/** Light and dark versions of the orange palette. */
private val OrangeSchemes = presetSchemes(
    lightPrimary = Color(0xFF994612),
    lightContainer = Color(0xFFFFDCC7),
    darkPrimary = Color(0xFFFFB68B),
    darkContainer = Color(0xFF793100),
)

/** Light and dark versions of the slate palette. */
private val SlateSchemes = presetSchemes(
    lightPrimary = Color(0xFF4C5E78),
    lightContainer = Color(0xFFD4E4FF),
    darkPrimary = Color(0xFFB3C7E6),
    darkContainer = Color(0xFF344764),
)

/** Applies Material 3 Expressive motion and typography with the selected color palette. */
@Composable
fun TelemetryCleanerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    palette: ThemePalette = ThemePalette.SYSTEM,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = resolveColorScheme(palette, darkTheme, dynamicColor),
        typography = Typography,
        content = content,
    )
}

/** Returns the primary color used by a palette under the current light or dark appearance. */
@Composable
internal fun ThemePalette.swatchColor(): Color =
    resolveColorScheme(this, isSystemInDarkTheme(), dynamicColor = true).primary

/** Resolves system colors when available, or chooses the light or dark preset. */
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

/** Builds both appearances for a preset from its accent and container colors. */
private fun presetSchemes(
    lightPrimary: Color,
    lightContainer: Color,
    darkPrimary: Color,
    darkContainer: Color,
): Pair<ColorScheme, ColorScheme> =
    presetScheme(lightPrimary, lightContainer, darkPrimary, darkTheme = false) to
        presetScheme(darkPrimary, darkContainer, lightPrimary, darkTheme = true)

/** Sets accent roles and lightly tints neutral surfaces to match the preset. */
private fun presetScheme(
    primary: Color,
    primaryContainer: Color,
    inversePrimary: Color,
    darkTheme: Boolean,
): ColorScheme {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val foreground = if (darkTheme) Color(0xFFE6E6E6) else Color(0xFF191919)
    val neutral = if (darkTheme) Color(0xFF111111) else Color(0xFFFFFFFF)
    val surface = lerp(neutral, primary, if (darkTheme) 0.015f else 0.035f)
    val secondary = lerp(primary, foreground, 0.30f)
    val secondaryContainer = lerp(surface, primaryContainer, 0.58f)
    val onAccent = if (darkTheme) lerp(Color.Black, primary, 0.20f) else Color.White
    val onPrimaryContainer = lerp(foreground, primary, 0.22f)

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
        tertiary = secondary,
        onTertiary = onAccent,
        tertiaryContainer = secondaryContainer,
        onTertiaryContainer = foreground,
        background = surface,
        onBackground = foreground,
        surface = surface,
        onSurface = foreground,
        onSurfaceVariant = lerp(foreground, surface, 0.22f),
        surfaceVariant = lerp(surface, foreground, 0.14f),
        surfaceTint = primary,
        surfaceContainerLowest = lerp(surface, neutral, 0.65f),
        surfaceContainerLow = lerp(surface, foreground, 0.04f),
        surfaceContainer = lerp(surface, foreground, 0.065f),
        surfaceContainerHigh = lerp(surface, foreground, 0.09f),
        surfaceContainerHighest = lerp(surface, foreground, 0.12f),
    )
}
