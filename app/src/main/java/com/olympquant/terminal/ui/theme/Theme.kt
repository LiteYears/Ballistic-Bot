package com.olympquant.terminal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olympquant.terminal.R

// True AMOLED Black & Neutral Carbon Surfaces
val ObsidianBackground = Color(0xFF000000)
val ObsidianElevated = Color(0xFF07090C)
val SlateSurface = Color(0xFF0A0D12)
val SlateSurfaceVariant = Color(0xFF11161E)
val SlateGlass = Color(0xFF151B24)
val SlateBorder = Color(0xFF1B222C)
val SlateBorderBright = Color(0xFF253040)

// Strict Palette: Subtle Trading Blue, Neon Trading Green, Neon Trading Red, Neutral Silver
val ElectricCyan = Color(0xFF2979FF)
val ElectricCyanDim = Color(0xFF102A56)
val NeonViolet = Color(0xFF2979FF)
val AmberGold = Color(0xFF94A3B8)
val BullishGreen = Color(0xFF00E676)
val BullishGreenDim = Color(0xFF002914)
val WarningAmber = Color(0xFFFFB300)
val WarningAmberDim = Color(0xFF332400)
val BearishCrimson = Color(0xFFFF1744)
val BearishCrimsonDim = Color(0xFF33050E)

val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

val CyberCardBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0B0E14),
        Color(0xFF05070A)
    )
)

val CyberHeaderBrush = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF05070A),
        Color(0xFF0B1018),
        Color(0xFF05070A)
    )
)

val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

private val QuantDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFFF8FAFC),
    primaryContainer = Color(0xFF0E2244),
    onPrimaryContainer = ElectricCyan,
    secondary = TextSecondary,
    onSecondary = ObsidianBackground,
    secondaryContainer = SlateSurfaceVariant,
    onSecondaryContainer = TextPrimary,
    tertiary = BullishGreen,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    outlineVariant = SlateBorderBright,
    error = BearishCrimson,
    onError = Color.White
)

val QuantShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

val QuantTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineMedium = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum"
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = "tnum"
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum"
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
        fontFeatureSettings = "tnum"
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun OlympQuantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QuantDarkColorScheme,
        typography = QuantTypography,
        shapes = QuantShapes,
        content = content
    )
}

