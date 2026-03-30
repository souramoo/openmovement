package org.openmovement.omgui.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Stitch Design Colors from step1-connect-sensor-simple.html
private val Primary = Color(0xFF033568)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFF254C80)
private val OnPrimaryContainer = Color(0xFF9ABEF9)

private val Secondary = Color(0xFF246488)
private val OnSecondary = Color(0xFFFFFFFF)
private val SecondaryContainer = Color(0xFF9CD6FF)
private val OnSecondaryContainer = Color(0xFF1B5D81)

private val Tertiary = Color(0xFF720009)
private val OnTertiary = Color(0xFFFFFFFF)
private val TertiaryContainer = Color(0xFF9D0011)
private val OnTertiaryContainer = Color(0xFFFFB3AC)

private val Error = Color(0xFFBA1A1A)
private val OnError = Color(0xFFFFFFFF)
private val ErrorContainer = Color(0xFFFFDAD6)
private val OnErrorContainer = Color(0xFF93000A)

private val Background = Color(0xFFF8F9FA)
private val OnBackground = Color(0xFF191C1D)

private val Surface = Color(0xFFF8F9FA)
private val OnSurface = Color(0xFF191C1D)
private val SurfaceDim = Color(0xFFD9DADB)
private val SurfaceBright = Color(0xFFF8F9FA)

private val SurfaceContainerLowest = Color(0xFFFFFFFF)
private val SurfaceContainerLow = Color(0xFFF3F4F5)
private val SurfaceContainer = Color(0xFFEDEEEF)
private val SurfaceContainerHigh = Color(0xFFE7E8E9)
private val SurfaceContainerHighest = Color(0xFFE1E3E4)

private val SurfaceVariant = Color(0xFFE1E3E4)
private val OnSurfaceVariant = Color(0xFF434652)

private val Outline = Color(0xFF737783)
private val OutlineVariant = Color(0xFFC3C6D4)

private val Scrim = Color(0xFF000000)
private val InverseSurface = Color(0xFF2E3132)
private val InverseOnSurface = Color(0xFFF0F1F2)
private val InversePrimary = Color(0xFFA7C8FF)

val ColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceDim = SurfaceDim,
    surfaceBright = SurfaceBright,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Scrim,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    inversePrimary = InversePrimary,
)

// Stitch Design Typography
// Based on: headline-md (28sp), title-md (18sp), body-lg (18sp), label-md (14sp)
// Font: Public Sans (using system default, which will gracefully fall back)
val StitchTypography = Typography(
    // Headline Large: 32sp, ExtraBold
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.ExtraBold,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    // Headline Medium: 28sp, ExtraBold
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    // Headline Small: 24sp, Bold
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    // Title Large: 22sp, Bold
    titleLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    // Title Medium: 18sp, SemiBold (title-md from Stitch)
    titleMedium = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    // Title Small: 14sp, SemiBold
    titleSmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // Body Large: 18sp, Regular (body-lg from Stitch)
    bodyLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    // Body Medium: 16sp, Regular
    bodyMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    // Body Small: 14sp, Regular
    bodySmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // Label Large: 14sp, SemiBold
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // Label Medium: 12sp, SemiBold (label-md from Stitch)
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    // Label Small: 11sp, SemiBold
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    // Display Large: 57sp, ExtraBold
    displayLarge = TextStyle(
        fontSize = 57.sp,
        fontWeight = FontWeight.ExtraBold,
        lineHeight = 64.sp,
        letterSpacing = 0.sp,
    ),
    // Display Medium: 45sp, ExtraBold
    displayMedium = TextStyle(
        fontSize = 45.sp,
        fontWeight = FontWeight.ExtraBold,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    // Display Small: 36sp, ExtraBold
    displaySmall = TextStyle(
        fontSize = 36.sp,
        fontWeight = FontWeight.ExtraBold,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
)

@Composable
fun AX3AppTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = StitchTypography,
        content = content,
    )
}
