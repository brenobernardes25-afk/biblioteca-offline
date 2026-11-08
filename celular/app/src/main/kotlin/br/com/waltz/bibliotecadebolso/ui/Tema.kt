package br.com.waltz.bibliotecadebolso.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "Manual de campo": papel, tinta, verde-oliva e um laranja de sinalização para o que pede atenção.
private val Claro = lightColorScheme(
    primary = Color(0xFF2F3B2A),
    onPrimary = Color(0xFFF7F4EA),
    secondary = Color(0xFFB4490F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF3EFE4),
    onBackground = Color(0xFF1B1C17),
    surface = Color(0xFFFBF9F2),
    onSurface = Color(0xFF1B1C17),
    surfaceVariant = Color(0xFFE5E0D0),
    onSurfaceVariant = Color(0xFF45463A),
    outline = Color(0xFF6E6F5F),
    outlineVariant = Color(0xFFCFCBBB),
    primaryContainer = Color(0xFFDDE5CC),
    onPrimaryContainer = Color(0xFF1A2214),
    secondaryContainer = Color(0xFFE5E0D0),
    onSecondaryContainer = Color(0xFF1B1C17),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4EA),
    surfaceContainer = Color(0xFFF0ECDF),
    surfaceContainerHigh = Color(0xFFEAE6D8),
    surfaceContainerHighest = Color(0xFFE5E0D0),
    error = Color(0xFFA3160F),
    onError = Color(0xFFFFFFFF),
)

private val Escuro = darkColorScheme(
    primary = Color(0xFFC8D6B4),
    onPrimary = Color(0xFF1A2214),
    secondary = Color(0xFFFFB37C),
    onSecondary = Color(0xFF3A1800),
    background = Color(0xFF12130F),
    onBackground = Color(0xFFEDEBE0),
    surface = Color(0xFF1C1D18),
    onSurface = Color(0xFFEDEBE0),
    surfaceVariant = Color(0xFF2B2C25),
    onSurfaceVariant = Color(0xFFCFCDBE),
    outline = Color(0xFF989888),
    outlineVariant = Color(0xFF45463A),
    primaryContainer = Color(0xFF3A4733),
    onPrimaryContainer = Color(0xFFDDE5CC),
    secondaryContainer = Color(0xFF3A3B32),
    onSecondaryContainer = Color(0xFFEDEBE0),
    surfaceContainerLowest = Color(0xFF0D0E0B),
    surfaceContainerLow = Color(0xFF181914),
    surfaceContainer = Color(0xFF1F201A),
    surfaceContainerHigh = Color(0xFF292A24),
    surfaceContainerHighest = Color(0xFF34352E),
    error = Color(0xFFFF9C90),
    onError = Color(0xFF3A0500),
)

private val Serifa = FontFamily.Serif

private val Letras = Typography(
    headlineLarge = TextStyle(fontFamily = Serifa, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Serifa, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Serifa, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
)

@Composable
fun TemaBolso(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Escuro else Claro,
        typography = Letras,
        shapes = Shapes(
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(6.dp),
            large = RoundedCornerShape(8.dp),
        ),
        content = conteudo,
    )
}
