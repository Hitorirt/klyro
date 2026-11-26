package gg.klyro.mapper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gg.klyro.mapper.R

/** Paleta do Klyro: preto, cinza-ardósia e azul (tirada da arte do mascote). */
object KlyroColors {
    val Background = Color(0xFF00000C)
    val Card = Color(0xFF0E1320)
    val CardElevated = Color(0xFF18202F)
    val Border = Color(0xFF2B3446)
    val Slate = Color(0xFF3C4860)
    val TextMuted = Color(0xFF9AA3B5)
    val Blue = Color(0xFF1F6BF5)
    val BlueDeep = Color(0xFF003CC0)
    val Cyan = Color(0xFF4D8DFF)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFF87171)
}

private val DarkColors = darkColorScheme(
    primary = KlyroColors.Blue,
    onPrimary = Color.White,
    primaryContainer = KlyroColors.BlueDeep,
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = KlyroColors.Cyan,
    onSecondary = Color(0xFF00222F),
    secondaryContainer = KlyroColors.CardElevated,
    onSecondaryContainer = Color(0xFFDCE3F5),
    tertiary = KlyroColors.Green,
    onTertiary = Color(0xFF052E1E),
    tertiaryContainer = Color(0xFF0E2420),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = KlyroColors.Background,
    onBackground = Color(0xFFF2F5FC),
    surface = KlyroColors.Background,
    onSurface = Color(0xFFF2F5FC),
    surfaceVariant = KlyroColors.CardElevated,
    onSurfaceVariant = KlyroColors.TextMuted,
    surfaceContainerLowest = Color(0xFF000008),
    surfaceContainerLow = KlyroColors.Card,
    surfaceContainer = Color(0xFF121826),
    surfaceContainerHigh = KlyroColors.CardElevated,
    surfaceContainerHighest = Color(0xFF212A3B),
    outline = KlyroColors.Slate,
    outlineVariant = KlyroColors.Border,
    error = KlyroColors.Red,
    errorContainer = Color(0xFF3B1519),
    onErrorContainer = Color(0xFFFECACA),
)

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val KlyroTypography = Typography().let { base ->
    Typography(
        displaySmall = base.displaySmall.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
        bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
        bodySmall = base.bodySmall.copy(fontFamily = Inter),
        labelLarge = base.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontFamily = Inter),
        labelSmall = base.labelSmall.copy(fontFamily = Inter),
    )
}

private val KlyroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Azul de destaque da marca. */
val KlyroBlue = KlyroColors.Blue

@Composable
@Suppress("UNUSED_PARAMETER")
fun KlyroTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = KlyroTypography,
        shapes = KlyroShapes,
        content = content,
    )
}
