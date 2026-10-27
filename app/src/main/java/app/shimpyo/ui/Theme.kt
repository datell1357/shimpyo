package app.shimpyo.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Night = Color(0xFF0A111C)
    val Panel = Color(0xFF131C2B)
    val PanelHigh = Color(0xFF1B2638)
    val Line = Color(0xFF26344A)
    val Text = Color(0xFFF2F4F8)
    val Sub = Color(0xFF98A3B3)
    val Amber = Color(0xFFFFB547)
    val AmberDeep = Color(0xFFE08A12)
    val Mint = Color(0xFF6FD3A6)
    val Sky = Color(0xFF7DB7FF)
    val Rose = Color(0xFFFF7A7A)
    val Dawn1 = Color(0xFF2A1F3D)
    val Dawn2 = Color(0xFF8A4B3A)
    val Dawn3 = Color(0xFFF3A65A)
}

private val scheme = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Color(0xFF231400),
    secondary = Palette.Sky,
    background = Palette.Night,
    onBackground = Palette.Text,
    surface = Palette.Panel,
    onSurface = Palette.Text,
    surfaceVariant = Palette.PanelHigh,
    onSurfaceVariant = Palette.Sub,
    outline = Palette.Line,
    error = Palette.Rose,
)

private val type = Typography(
    displayLarge = TextStyle(fontSize = 88.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontSize = 60.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 19.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ShimpyoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type) {
        // Pleos 앱 영역은 mdpi(160dpi) 큰 화면이라 기본 배율로는 글자가 작다 → 넓은 창에서 1.2배
        val config = LocalConfiguration.current
        val base = LocalDensity.current
        val scale = if (config.smallestScreenWidthDp >= 900) 1.2f else 1f
        CompositionLocalProvider(
            LocalContentColor provides Palette.Text,
            LocalDensity provides Density(base.density * scale, base.fontScale),
            content = content,
        )
    }
}
