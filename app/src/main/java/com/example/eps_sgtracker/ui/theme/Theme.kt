package com.example.eps_sgtracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

// The app is deliberately dark-only - it is a night-sky tracking tool and the globe renderer
// assumes a dark ground. There is intentionally no isSystemInDarkTheme() branch here; MainActivity
// pins the system bars dark to match, rather than letting them follow the system night setting.
private val AppColorScheme = darkColorScheme(
    background = Background,
    surface = SurfaceContainer,
    surfaceVariant = SurfaceContainerHigh,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    primary = Primary,
    secondary = Secondary,
    outline = Outline
)

/**
 * [primaryOverride] replaces the accent color used for headings, section labels, the selected
 * navigation item and similar highlights. Null keeps the shipped accent.
 *
 * onPrimaryContainer is overridden alongside it because that is the text color the app's colored
 * pills draw with, and those pills are filled with arbitrary per-satellite/per-station colors - it
 * has to stay a fixed dark tone rather than track the accent, or a light theme color would make
 * pill labels unreadable.
 */
@Composable
fun EPSSGTrackerTheme(primaryOverride: Color? = null, content: @Composable () -> Unit) {
    val colorScheme = remember(primaryOverride) {
        if (primaryOverride == null) {
            AppColorScheme
        } else {
            AppColorScheme.copy(primary = primaryOverride)
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}