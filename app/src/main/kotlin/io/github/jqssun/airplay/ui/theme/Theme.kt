package io.github.jqssun.airplay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import io.github.jqssun.airplay.ui.isTv

/** Everything a 10-foot screen reads has to be bigger than what a phone reads. */
private const val TV_TYPE_SCALE = 1.25f

private fun TextStyle.scaled(f: Float) = copy(fontSize = fontSize * f, lineHeight = lineHeight * f)

@Composable
private fun tvTypography(base: Typography, f: Float = TV_TYPE_SCALE) = Typography(
    displayLarge = base.displayLarge.scaled(f),
    displayMedium = base.displayMedium.scaled(f),
    displaySmall = base.displaySmall.scaled(f),
    headlineLarge = base.headlineLarge.scaled(f),
    headlineMedium = base.headlineMedium.scaled(f),
    headlineSmall = base.headlineSmall.scaled(f),
    titleLarge = base.titleLarge.scaled(f),
    titleMedium = base.titleMedium.scaled(f),
    titleSmall = base.titleSmall.scaled(f),
    bodyLarge = base.bodyLarge.scaled(f),
    bodyMedium = base.bodyMedium.scaled(f),
    bodySmall = base.bodySmall.scaled(f),
    labelLarge = base.labelLarge.scaled(f),
    labelMedium = base.labelMedium.scaled(f),
    labelSmall = base.labelSmall.scaled(f),
)

@Composable
fun AirPlayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    // the mobile scale is unreadable across a room; the debug overlay and the
    // log window inherit this because they take their style from the theme
    val typography = if (isTv()) tvTypography(Typography()) else Typography()

    MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
}
