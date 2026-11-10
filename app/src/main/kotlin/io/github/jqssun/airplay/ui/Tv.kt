package io.github.jqssun.airplay.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Context.isTvDevice(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun isTv(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { ctx.isTvDevice() }
}

/**
 * Enhanced TV focus indicator with animations
 * - White border (3dp) when focused
 * - Scale animation (1.05x) with spring physics
 * - Elevation shadow for depth
 */
fun Modifier.dpadFocus(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.outline else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "focusBorder"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1.0f,  // Reduced from 1.05x
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "focusScale"
    )
    
    val elevation by animateDpAsState(
        targetValue = if (focused) 8.dp else 0.dp,
        animationSpec = tween(durationMillis = 150),
        label = "focusElevation"
    )
    
    this
        .shadow(elevation = elevation, shape = shape)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 3.dp else 0.dp,  // Reduced from 4dp
            color = borderColor,
            shape = shape
        )
        .clip(shape)
}

/**
 * Custom TV focus border for cards and buttons
 * Allows customization of border width and shape
 */
fun Modifier.tvFocusBorder(
    shape: Shape = RoundedCornerShape(12.dp),
    borderWidth: Dp = 3.dp,  // Reduced from 4dp
    unfocusedBorderWidth: Dp = 0.dp,
    scaleOnFocus: Boolean = true,
    elevationOnFocus: Boolean = true
) = composed {
    var isFocused by remember { mutableStateOf(false) }
    
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.outline else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "tvFocusBorder"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isFocused && scaleOnFocus) 1.02f else 1.0f,  // Reduced from 1.05x
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tvFocusScale"
    )
    
    val elevation by animateDpAsState(
        targetValue = if (isFocused && elevationOnFocus) 8.dp else 0.dp,
        animationSpec = tween(durationMillis = 150),
        label = "tvFocusElevation"
    )
    
    this
        .shadow(elevation = elevation, shape = shape)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .onFocusChanged { isFocused = it.isFocused }
        .border(
            width = if (isFocused) borderWidth else unfocusedBorderWidth,
            color = borderColor,
            shape = shape
        )
}

/**
 * TV focus without scale animation - for Settings items that might clip
 */
fun Modifier.dpadFocusNoScale(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.outline else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "focusBorder"
    )
    
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = borderColor,
            shape = shape
        )
        .clip(shape)
}

fun Modifier.dpadAdjust(onLeft: () -> Unit, onRight: () -> Unit): Modifier = composed {
    val focus = LocalFocusManager.current
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { onLeft(); true }
            Key.DirectionRight -> { onRight(); true }
            // slider
            Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
            Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
            else -> false
        }
    }
}
