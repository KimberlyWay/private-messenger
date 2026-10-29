@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package uk.mywisp.privatechat

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import uk.mywisp.material.hct.Hct
import uk.mywisp.material.scheme.SchemeTonalSpot

val LocalAppearance = staticCompositionLocalOf { Appearance() }

internal fun seedColor(appearance: Appearance): Int = when (appearance.accent) {
    "ocean" -> 0xFF006783.toInt()
    "forest" -> 0xFF286B42.toInt()
    "rose" -> 0xFF9C4167.toInt()
    "peach" -> 0xFF9C4325.toInt()
    "custom" -> Hct.from(appearance.hue.toDouble(), 60.0, 50.0).toInt()
    else -> 0xFF8E7BFF.toInt()
}

/** Every role comes from the same Google HCT scheme, including tonal surfaces and chips. */
internal fun tonalScheme(seed: Int, dark: Boolean, contrast: Float): ColorScheme {
    val s = SchemeTonalSpot(Hct.fromInt(seed), dark, contrast.toDouble(),
        uk.mywisp.material.dynamiccolor.ColorSpec.SpecVersion.SPEC_2026,
        uk.mywisp.material.dynamiccolor.DynamicScheme.Platform.PHONE)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(s.primary), onPrimary = Color(s.onPrimary),
        primaryContainer = Color(s.primaryContainer), onPrimaryContainer = Color(s.onPrimaryContainer),
        inversePrimary = Color(s.inversePrimary), secondary = Color(s.secondary), onSecondary = Color(s.onSecondary),
        secondaryContainer = Color(s.secondaryContainer), onSecondaryContainer = Color(s.onSecondaryContainer),
        tertiary = Color(s.tertiary), onTertiary = Color(s.onTertiary),
        tertiaryContainer = Color(s.tertiaryContainer), onTertiaryContainer = Color(s.onTertiaryContainer),
        primaryFixed = Color(s.primaryFixed), primaryFixedDim = Color(s.primaryFixedDim),
        onPrimaryFixed = Color(s.onPrimaryFixed), onPrimaryFixedVariant = Color(s.onPrimaryFixedVariant),
        secondaryFixed = Color(s.secondaryFixed), secondaryFixedDim = Color(s.secondaryFixedDim),
        onSecondaryFixed = Color(s.onSecondaryFixed), onSecondaryFixedVariant = Color(s.onSecondaryFixedVariant),
        tertiaryFixed = Color(s.tertiaryFixed), tertiaryFixedDim = Color(s.tertiaryFixedDim),
        onTertiaryFixed = Color(s.onTertiaryFixed), onTertiaryFixedVariant = Color(s.onTertiaryFixedVariant),
        background = Color(s.background), onBackground = Color(s.onBackground),
        surface = Color(s.surface), onSurface = Color(s.onSurface), surfaceVariant = Color(s.surfaceVariant),
        onSurfaceVariant = Color(s.onSurfaceVariant), surfaceTint = Color(s.surfaceTint),
        inverseSurface = Color(s.inverseSurface), inverseOnSurface = Color(s.inverseOnSurface),
        surfaceBright = Color(s.surfaceBright), surfaceDim = Color(s.surfaceDim),
        surfaceContainerLowest = Color(s.surfaceContainerLowest), surfaceContainerLow = Color(s.surfaceContainerLow),
        surfaceContainer = Color(s.surfaceContainer), surfaceContainerHigh = Color(s.surfaceContainerHigh),
        surfaceContainerHighest = Color(s.surfaceContainerHighest),
        outline = Color(s.outline), outlineVariant = Color(s.outlineVariant), scrim = Color(s.scrim),
        error = Color(s.error), onError = Color(s.onError),
        errorContainer = Color(s.errorContainer), onErrorContainer = Color(s.onErrorContainer),
    )
}

@Composable
fun PrivateTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (appearance.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val systemColors = if (appearance.accent == "dynamic" && android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    val scheme = remember(appearance, dark, systemColors) {
        val normal = if (systemColors != null && appearance.contrast == 0f) systemColors
            else tonalScheme(systemColors?.primary?.toArgb() ?: seedColor(appearance), dark, appearance.contrast)
        if (dark && appearance.amoled) normal.copy(background = Color.Black, surface = Color.Black,
            surfaceDim = Color.Black, surfaceContainerLowest = Color.Black, surfaceContainerLow = Color.Black) else normal
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialExpressiveTheme(colorScheme = scheme, content = content)
    }
}
