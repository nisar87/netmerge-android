package dev.netmerge.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** NetMerge's colours: blue for Wi-Fi and the brand, teal for mobile data, a warm red for "stop". */
object Brand {
    val Blue = Color(0xFF1E6EE6)
    val BlueLight = Color(0xFF8AB4FF)
    val Teal = Color(0xFF12A594)
    val TealLight = Color(0xFF5FD4C4)
    val Red = Color(0xFFD9483B)
    val Amber = Color(0xFFE8A13A)
}

private val Light = lightColorScheme(
    primary = Brand.Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF), onPrimaryContainer = Color(0xFF0B2A5E),
    secondary = Brand.Teal, onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F4EF), onSecondaryContainer = Color(0xFF00382F),
    tertiary = Brand.Amber,
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF14181F),
    surface = Color(0xFFF6F8FB), onSurface = Color(0xFF14181F),
    surfaceVariant = Color(0xFFE6EAF0), onSurfaceVariant = Color(0xFF5A6270),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEFF2F6),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainerHighest = Color(0xFFE6EAF0),
    outline = Color(0xFFC3CAD4), outlineVariant = Color(0xFFDDE2E9),
    error = Brand.Red, errorContainer = Color(0xFFFDE3E0), onErrorContainer = Color(0xFF5C120B),
)

private val Dark = darkColorScheme(
    primary = Brand.BlueLight, onPrimary = Color(0xFF062A63),
    primaryContainer = Color(0xFF1B3F7A), onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Brand.TealLight, onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0F4F46), onSecondaryContainer = Color(0xFFD3F4EF),
    tertiary = Brand.Amber,
    background = Color(0xFF0E1116), onBackground = Color(0xFFE6E9EE),
    surface = Color(0xFF0E1116), onSurface = Color(0xFFE6E9EE),
    surfaceVariant = Color(0xFF232933), onSurfaceVariant = Color(0xFF9AA3B0),
    surfaceContainer = Color(0xFF171B22), surfaceContainerHigh = Color(0xFF1E232C),
    surfaceContainerLow = Color(0xFF13171D), surfaceContainerHighest = Color(0xFF262C36),
    outline = Color(0xFF3A414D), outlineVariant = Color(0xFF2A303A),
    error = Color(0xFFFF8A7E), errorContainer = Color(0xFF4A1A15), onErrorContainer = Color(0xFFFFDAD5),
)

private val Type = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Numbers that change every second: same width digits, so they do not jump. */
val Numbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun NetMergeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
