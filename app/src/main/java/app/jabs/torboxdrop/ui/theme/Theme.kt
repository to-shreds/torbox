package app.jabs.torboxdrop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Brand colors shared by screens that need semantic colors outside Material's
 * standard color roles (success, warning, gradients, and so on).
 */
@Immutable
object TorBoxColors {
    val Night = Color(0xFF071018)
    val NightRaised = Color(0xFF0C1822)
    val Card = Color(0xFF112330)
    val CardLow = Color(0xFF0D1D28)
    val CardHigh = Color(0xFF183242)
    val Divider = Color(0xFF254050)

    val Text = Color(0xFFF6FBFF)
    val TextMuted = Color(0xFF9DB1BE)

    val Teal = Color(0xFF2ED5C4)
    val TealBright = Color(0xFF79F2E6)
    val Blue = Color(0xFF5AA8FF)
    val Green = Color(0xFF68E08C)
    val Amber = Color(0xFFFFC766)
    val Red = Color(0xFFFF6F7D)

    val HeroGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF173F48), Color(0xFF102B3C), Color(0xFF10222E)),
    )
    val AccentGradient = Brush.linearGradient(
        colors = listOf(TealBright, Teal),
    )
}

private val DarkColors = darkColorScheme(
    primary = TorBoxColors.TealBright,
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF07524D),
    onPrimaryContainer = Color(0xFFB4FFF7),
    inversePrimary = Color(0xFF006A62),
    secondary = Color(0xFF92C7FF),
    onSecondary = Color(0xFF003258),
    secondaryContainer = Color(0xFF174B70),
    onSecondaryContainer = Color(0xFFD2E9FF),
    tertiary = Color(0xFFFFD188),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF614200),
    onTertiaryContainer = Color(0xFFFFDEA5),
    error = Color(0xFFFFB3BA),
    onError = Color(0xFF68001A),
    errorContainer = Color(0xFF8E2634),
    onErrorContainer = Color(0xFFFFDADB),
    background = TorBoxColors.Night,
    onBackground = TorBoxColors.Text,
    surface = TorBoxColors.NightRaised,
    onSurface = TorBoxColors.Text,
    surfaceVariant = TorBoxColors.Card,
    onSurfaceVariant = Color(0xFFC4D4DC),
    surfaceTint = TorBoxColors.Teal,
    inverseSurface = Color(0xFFDCE5E9),
    inverseOnSurface = Color(0xFF172126),
    outline = Color(0xFF527080),
    outlineVariant = TorBoxColors.Divider,
    scrim = Color.Black,
)

// A restrained light scheme keeps previews and system-driven light mode usable.
// The app defaults to the dark scheme to retain TorBox Drop's visual identity.
private val LightColors = lightColorScheme(
    primary = Color(0xFF006A62),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9DF2E8),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF29618B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE5FF),
    onSecondaryContainer = Color(0xFF001D32),
    tertiary = Color(0xFF785900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA5),
    onTertiaryContainer = Color(0xFF251A00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF3FAF9),
    onBackground = Color(0xFF151D1C),
    surface = Color(0xFFF8FAF9),
    onSurface = Color(0xFF151D1C),
    surfaceVariant = Color(0xFFD9E5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    scrim = Color.Black,
)

val TorBoxTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
        lineHeight = 31.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.35.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.55.sp,
    ),
)

@Composable
fun TorBoxDropTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TorBoxTypography,
        content = content,
    )
}

/** System-aware variant for isolated previews or embedders that want it. */
@Composable
fun TorBoxDropSystemTheme(content: @Composable () -> Unit) {
    TorBoxDropTheme(darkTheme = isSystemInDarkTheme(), content = content)
}
