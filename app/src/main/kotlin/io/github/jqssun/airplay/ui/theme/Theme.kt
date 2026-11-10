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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.ui.isTv

// TV-optimized color scheme (high contrast for 10-foot viewing)
private val TvColorScheme = darkColorScheme(
    primary = Color(0xFFFF9900),            // Amazon orange
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF663D00),
    onPrimaryContainer = Color(0xFFFFDDB3),
    
    secondary = Color(0xFFFFFFFF),          // White for better visibility
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF424242),
    onSecondaryContainer = Color(0xFFFFFFFF),
    
    tertiary = Color(0xFF7CB342),           // Green - success
    onTertiary = Color(0xFF000000),
    
    background = Color(0xFF000000),         // Pure black for TV
    onBackground = Color(0xFFFFFFFF),
    
    surface = Color(0xFF1A1A1A),            // Card background
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFE0E0E0),
    
    outline = Color(0xFFFFFFFF),            // Focus border WHITE (not yellow)
    outlineVariant = Color(0xFF404040),     // Dividers
    
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000),
)

// TV-optimized typography (18sp minimum for 10-foot viewing)
private val TvTypography = Typography(
    // Display - Hero text
    displayLarge = TextStyle(
        fontSize = 57.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontSize = 45.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold
    ),
    displaySmall = TextStyle(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold
    ),
    
    // Headline - Section headers
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold
    ),
    
    // Title - Card titles
    titleLarge = TextStyle(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium
    ),
    titleMedium = TextStyle(
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium
    ),
    titleSmall = TextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium
    ),
    
    // Body - Main content (MINIMUM 18sp for TV)
    bodyLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 18.sp,  // TV-SAFE MINIMUM
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 18.sp,  // Never below 18sp on TV
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal
    ),
    
    // Label - Buttons
    labelLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontSize = 18.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun AirPlayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val isTvDevice = isTv()
    
    val colorScheme = when {
        // TV devices: use custom high-contrast scheme
        isTvDevice -> TvColorScheme
        
        // Mobile devices: use dynamic colors on Android 12+
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        
        // Fallback: Material3 defaults
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    // Use TV-optimized typography on TV devices for 10-foot viewing
    val typography = if (isTvDevice) TvTypography else Typography()

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
