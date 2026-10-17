package inc.pazuzu.imzo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Always dark and high contrast, whatever the system setting: readable at arm's length, no glare. */
val ApproveGreen = Color(0xFF00E676)
val RejectRed = Color(0xFFFF5252)
val WarnAmber = Color(0xFFFFD740)

private val HighContrastScheme = darkColorScheme(
    primary = Color(0xFF82B1FF),
    onPrimary = Color.Black,
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFE0E0E0),
    error = RejectRed,
    onError = Color.Black,
    outline = Color(0xFF9E9E9E),
)

@Composable
fun ImzoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HighContrastScheme, content = content)
}
