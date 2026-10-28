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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 무채색 위에 주황 한 점. 색은 정보가 있을 때만(진행 중·주의) 쓰고, 나머지는 흰색의 투명도로 위계를 만든다.
 */
object Palette {
    val Ink = Color(0xFF0A0A0A)          // 기본 바탕
    val Sleep = Color(0xFF000000)        // 쉼표 중 — 빛을 최소로
    val Surface = Color(0xFF141414)      // 카드
    val Raised = Color(0xFF1F1F1F)       // 보조 버튼
    val Hairline = Color(0x1AFFFFFF)     // 1px 선(흰 10%)
    val HairlineStrong = Color(0x29FFFFFF)
    val Text = Color(0xFFF5F5F5)
    val Muted = Color(0x80FFFFFF)        // 흰 50%
    val Faint = Color(0x4DFFFFFF)        // 흰 30%
    val Accent = Color(0xFFFF8400)       // 쉼표의 ",", 진행 중, 주의
    val OnLight = Color(0xFF0A0A0A)
}

private val scheme = darkColorScheme(
    primary = Palette.Text,
    onPrimary = Palette.OnLight,
    secondary = Palette.Accent,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Hairline,
    error = Palette.Accent,
)

/** 큰 숫자: 가늘게, 자리 폭 고정(시계·거리가 흔들리지 않게), 자간을 조금 좁게. */
val Numeral = TextStyle(fontWeight = FontWeight.Light, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum")

/** 단위·순번 같은 짧은 라틴 표기. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 0.04.em)

// 한글은 받침 때문에 줄이 빽빽해 보여 행간을 글자 크기의 1.3(제목)~1.6배(본문)로 둔다
private val type = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 72.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 58.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em),
    headlineLarge = TextStyle(fontSize = 36.sp, lineHeight = 50.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 29.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em),
    labelMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
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
