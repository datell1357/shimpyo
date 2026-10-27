package app.shimpyo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AirlineSeatReclineExtra
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shimpyo.core.Phase
import app.shimpyo.core.ShimpyoController
import app.shimpyo.core.UiState
import app.shimpyo.core.WakeStep
import app.shimpyo.vehicle.Gear
import java.util.Locale
import kotlin.math.roundToInt

class Actions(
    val accept: () -> Unit,
    val decline: () -> Unit,
    val restNow: () -> Unit,
    val startRest: (Int) -> Unit,
    val wakeNow: () -> Unit,
    val awake: () -> Unit,
    val extend: () -> Unit,
    val depart: () -> Unit,
    val setDemo: (Boolean) -> Unit,
    val demoAdd: (Int) -> Unit,
    val simGear: ((Gear) -> Unit)?,
    val simBelt: ((Boolean) -> Unit)?,
    val simDoor: ((Boolean) -> Unit)?,
)

@Composable
fun ShimpyoScreen(
    s: UiState,
    spoken: String?,
    heard: String?,
    listening: Boolean,
    a: Actions,
) {
    var showSettings by remember { mutableStateOf(false) }
    val bg = when (s.phase) {
        Phase.RESTING -> Brush.verticalGradient(listOf(Color(0xFF05080E), Color(0xFF0B1220)))
        Phase.WAKING, Phase.DEPARTING -> Brush.verticalGradient(listOf(Palette.Dawn1, Palette.Dawn2, Palette.Dawn3))
        else -> Brush.verticalGradient(listOf(Palette.Night, Color(0xFF0F1828)))
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val wide = maxWidth > maxHeight * 1.3f
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            TopBar(s, onSettings = { showSettings = true })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = s.phase,
                    transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
                    label = "phase",
                ) { phase ->
                    when (phase) {
                        Phase.DRIVING -> Driving(s, wide, a)
                        Phase.SUGGESTING -> Suggest(s, listening, a)
                        Phase.HEADING -> Heading(s)
                        Phase.PARKED -> Parked(s, a)
                        Phase.RESTING -> Resting(s, a)
                        Phase.WAKING -> Waking(s, a)
                        Phase.DEPARTING -> Departing(s, a)
                    }
                }
            }
            VoiceBar(spoken, heard, listening, dim = s.phase == Phase.RESTING,
                hint = if (s.askingAwake) "듣고 있어요 · ‘네’ 또는 ‘5분만 더’" else "듣고 있어요 · ‘응’ 또는 ‘아니’")
        }
        if (showSettings && !s.restricted) Settings(s, a, onClose = { showSettings = false })
    }
}

// ---------------- 공통 ----------------

@Composable
private fun TopBar(s: UiState, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append("쉼표")
                withStyle(SpanStyle(color = Palette.Amber)) { append(",") }
            },
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            modifier = Modifier.alpha(if (s.phase == Phase.RESTING) 0.45f else 1f),
        )
        Spacer(Modifier.width(20.dp))
        val car = when {
            s.simulated -> "가상 차량" to Palette.Sub
            s.vehicleConnected -> "차량 연결됨" to Palette.Mint
            else -> "차량 연결 중" to Palette.Amber
        }
        StatusChip(car.first, car.second)
        if (s.naviAvailable) StatusChip("내비 연동", Palette.Sky)
        if (s.demo) StatusChip("시연 모드 · 3초=1분", Palette.Amber)
        Spacer(Modifier.weight(1f))
        GearBadge(s.gear)
        if (!s.restricted) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Settings, "설정", tint = Palette.Sub, modifier = Modifier.size(30.dp)) }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Row(
        Modifier.padding(end = 10.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun GearBadge(g: Gear) {
    val label = when (g) {
        Gear.PARK -> "P"; Gear.DRIVE -> "D"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.UNKNOWN -> "–"
    }
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).border(2.dp, Palette.Line, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleLarge, color = if (g == Gear.PARK) Palette.Mint else Palette.Text) }
}

@Composable
private fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 96.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.heightIn(min = height).clip(shape)
            .then(if (primary) Modifier.background(Brush.horizontalGradient(listOf(Palette.Amber, Palette.AmberDeep)))
            else Modifier.background(Palette.PanelHigh).border(2.dp, Palette.Line, shape))
            .clickable(onClick = onClick).padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (primary) Color(0xFF231400) else Palette.Text, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 28.sp),
            color = if (primary) Color(0xFF231400) else Palette.Text)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(Palette.Panel.copy(alpha = 0.92f))
            .border(1.dp, Palette.Line, RoundedCornerShape(28.dp)).padding(28.dp),
        content = content,
    )
}

@Composable
private fun VoiceBar(spoken: String?, heard: String?, listening: Boolean, dim: Boolean, hint: String) {
    val pulse = rememberInfiniteTransition(label = "mic").animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.28f))
            .padding(horizontal = 22.dp, vertical = 12.dp).alpha(if (dim) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (listening) {
            Icon(Icons.Rounded.Mic, null, tint = Palette.Amber, modifier = Modifier.size(30.dp).alpha(pulse.value))
            Spacer(Modifier.width(14.dp))
            Text(heard?.let { "“$it”" } ?: hint, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
        } else {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Palette.Sub, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Text(spoken ?: "운전 중에는 말로 안내해요.", style = MaterialTheme.typography.bodyLarge, color = Palette.Sub,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun dur(min: Int) = ShimpyoController.durationText(min)
private fun km(v: Double) = if (v < 10) String.format(Locale.KOREA, "%.1f", v) else v.roundToInt().toString()

// ---------------- 주행 ----------------

@Composable
private fun Driving(s: UiState, wide: Boolean, a: Actions) {
    val left: @Composable ColumnScope.() -> Unit = {
        Text("연속 운전", style = MaterialTheme.typography.titleMedium, color = Palette.Sub)
        Text(dur(s.continuousMin), style = MaterialTheme.typography.displayMedium, color = Palette.Text)
        Spacer(Modifier.height(18.dp))
        val ratio = (s.continuousMin.toFloat() / s.thresholdMin).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.Line)) {
            Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                .background(if (ratio >= 0.9f) Palette.Amber else Palette.Sky))
        }
        Spacer(Modifier.height(12.dp))
        val left = (s.thresholdMin - s.continuousMin).coerceAtLeast(0)
        Text(
            if (left > 0) "쉬어 가기 권장까지 ${dur(left)} · 기준 ${dur(s.thresholdMin)}" else "쉬어 갈 때가 됐어요",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Sub,
        )
    }
    val right: @Composable ColumnScope.() -> Unit = {
        NextAreaPanel(s)
        Spacer(Modifier.height(20.dp))
        BigButton("지금 쉬어 가기", Modifier.fillMaxWidth(), icon = Icons.Rounded.Bedtime, onClick = a.restNow)
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), content = left)
            Column(Modifier.weight(1f), content = right)
        }
    } else {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) { left(); Spacer(Modifier.height(36.dp)); right() }
    }
}

@Composable
private fun NextAreaPanel(s: UiState) {
    Panel(Modifier.fillMaxWidth()) {
        Text("다음 졸음쉼터", style = MaterialTheme.typography.titleMedium, color = Palette.Sub)
        Spacer(Modifier.height(8.dp))
        val hit = s.nextArea
        if (hit == null) {
            Text("주변 쉼터를 찾는 중", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hit.area.name, style = MaterialTheme.typography.headlineLarge, color = Palette.Text)
                Spacer(Modifier.width(14.dp))
                Text("${km(hit.distanceKm)} km", style = MaterialTheme.typography.headlineMedium, color = Palette.Amber)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Meta(Icons.Rounded.Navigation, hit.area.routeName)
                if (hit.area.hasToilet) Meta(Icons.Rounded.Wc, "화장실")
                if (hit.area.parkingSpaces > 0) Meta(Icons.Rounded.LocalParking, "${hit.area.parkingSpaces}면")
            }
        }
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) {
    Icon(icon, null, tint = Palette.Sub, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Sub)
    Spacer(Modifier.width(18.dp))
}

// ---------------- 제안 ----------------

@Composable
private fun Suggest(s: UiState, listening: Boolean, a: Actions) {
    val area = s.target
    Panel(Modifier.widthIn(max = 980.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Palette.Amber.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Bedtime, null, tint = Palette.Amber, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(20.dp))
            Text("쉬어 갈까요?", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            buildAnnotatedString {
                append("${dur(s.continuousMin)}째 운전 중이에요.\n")
                if (area != null) {
                    val d = s.nextArea?.takeIf { it.area == area }?.distanceKm
                    if (d != null) append("${km(d)}km 앞 ")
                    withStyle(SpanStyle(color = Palette.Amber, fontWeight = FontWeight.Bold)) { append(area.displayName) }
                    append("에서 잠깐 쉬어 가요.")
                }
            },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 26.sp, lineHeight = 38.sp),
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton("쉬어 갈게요", Modifier.weight(1.4f), onClick = a.accept)
            BigButton("다음에", Modifier.weight(1f), primary = false, onClick = a.decline)
        }
        if (listening) {
            Spacer(Modifier.height(14.dp))
            Text("말로 대답해도 돼요", style = MaterialTheme.typography.bodyMedium, color = Palette.Sub)
        }
    }
}

// ---------------- 쉼터로 가는 중 ----------------

@Composable
private fun Heading(s: UiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Navigation, null, tint = Palette.Sky, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("쉼터로 가는 중", style = MaterialTheme.typography.titleLarge, color = Palette.Sub)
        Text(s.target?.displayName ?: "졸음쉼터", style = MaterialTheme.typography.headlineLarge)
        s.targetDistanceKm?.let {
            Text("${km(it)} km", style = MaterialTheme.typography.displayMedium, color = Palette.Amber)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (s.arrived) "도착했어요. P단으로 바꾸면 쉼표를 시작해요." else "도착해서 P단으로 바꾸면 쉼표를 시작해요.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.Sub, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        // 카페인 냅: 커피를 마시고 바로 짧게 자면, 깰 때쯤 카페인 효과가 난다
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Palette.Amber.copy(alpha = 0.14f)).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.LocalCafe, null, tint = Palette.Amber, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("커피가 있으면 쉬기 직전에 — 깰 때쯤 효과가 나요", style = MaterialTheme.typography.bodyLarge, color = Palette.Amber)
        }
    }
}

// ---------------- 정차(P단) ----------------

@Composable
private fun Parked(s: UiState, a: Actions) {
    Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth()) {
        Text(
            if (s.arrived && s.target != null) "${s.target.displayName}에 도착했어요" else "잠깐 쉬어 갈까요?",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text("쉼표 시간을 고르면 시트·공기·조명을 맞추고 정한 시간에 깨워 드려요.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.Sub)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(15, 20, 25).forEach { m ->
                DurationTile(m, recommended = m == 20, modifier = Modifier.weight(1f), busy = s.busy) { a.startRest(m) }
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FeatureChip(Icons.Rounded.AirlineSeatReclineExtra, "시트", s.features.seatRecline)
            FeatureChip(Icons.Rounded.Air, "외기·온도", s.features.climate)
            FeatureChip(Icons.Rounded.Window, "창문", s.features.window)
            FeatureChip(Icons.Rounded.Lightbulb, "조명", s.features.cabinLight || s.features.ambientLight)
            FeatureChip(Icons.Rounded.Lock, "문 잠김", s.features.doorLock)
        }
    }
}

@Composable
private fun DurationTile(min: Int, recommended: Boolean, modifier: Modifier, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.height(170.dp).clip(shape)
            .background(if (recommended) Palette.Amber.copy(alpha = 0.16f) else Palette.Panel)
            .border(2.dp, if (recommended) Palette.Amber else Palette.Line, shape)
            .clickable(enabled = !busy, onClick = onClick).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$min", style = MaterialTheme.typography.displayMedium, color = if (recommended) Palette.Amber else Palette.Text)
        Text(if (recommended) "분 · 추천" else "분", style = MaterialTheme.typography.titleMedium, color = Palette.Sub)
    }
}

@Composable
private fun FeatureChip(icon: ImageVector, label: String, on: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Mint.copy(alpha = 0.14f) else Palette.Panel)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) Palette.Mint else Palette.Sub, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (on) label else "$label · 안내만", style = MaterialTheme.typography.labelMedium, color = if (on) Palette.Mint else Palette.Sub)
    }
}

// ---------------- 쉼표 중 ----------------

@Composable
private fun Resting(s: UiState, a: Actions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(340.dp), contentAlignment = Alignment.Center) {
            val progress = if (s.restTotalMs > 0) 1f - s.restRemainingMs.toFloat() / s.restTotalMs else 0f
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val size = Size(size.width - stroke, size.height - stroke)
                drawArc(Palette.Line, -90f, 360f, false, Offset(inset, inset), size, style = Stroke(stroke))
                drawArc(Palette.Amber.copy(alpha = 0.85f), -90f, 360f * progress, false, Offset(inset, inset), size,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(clock(s.restRemainingMs, s.demo), style = MaterialTheme.typography.displayLarge, color = Palette.Text.copy(alpha = 0.92f))
                Text(if (s.paused) "문 열림 · 잠시 멈춤" else "쉼표 중", style = MaterialTheme.typography.titleLarge,
                    color = if (s.paused) Palette.Amber else Palette.Sub)
            }
        }
        Spacer(Modifier.width(56.dp))
        Column(Modifier.widthIn(max = 560.dp)) {
            Text(if (s.paused) "문을 닫으면 이어 가요" else "편하게 쉬세요", style = MaterialTheme.typography.headlineMedium,
                color = Palette.Text.copy(alpha = 0.9f))
            Spacer(Modifier.height(6.dp))
            Text("깊은 잠에 들기 전에 깨워 개운하게 일어나요", style = MaterialTheme.typography.bodyMedium, color = Palette.Sub)
            Spacer(Modifier.height(14.dp))
            s.applied.forEach { Done(it) }
            s.manualHints.forEach { Hint(it) }
            Spacer(Modifier.height(24.dp))
            BigButton("지금 일어날게요", primary = false, height = 76.dp, onClick = a.wakeNow)
        }
    }
}

/** 시연 모드에서는 '분'을 3초로 줄였으므로 화면에는 실제 쉼표 시간으로 환산해 보여 준다. */
private fun clock(remainingMs: Long, demo: Boolean): String {
    val minuteMs = if (demo) ShimpyoController.DEMO_MINUTE_MS else 60_000L
    val totalSec = ((remainingMs * 60_000L / minuteMs) / 1000L).toInt()
    return String.format(Locale.KOREA, "%d:%02d", totalSec / 60, totalSec % 60)
}

@Composable
private fun Done(text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Check, null, tint = Palette.Mint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.85f))
    }
}

@Composable
private fun Hint(text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.WbTwilight, null, tint = Palette.Amber, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.Amber)
    }
}

// ---------------- 깨우기 ----------------

@Composable
private fun Waking(s: UiState, a: Actions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Icon(Icons.Rounded.WbTwilight, null, tint = Color(0xFFFFE2B0), modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(10.dp))
            Text("천천히 깨워 드릴게요", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("빛 → 바람 → 자세 → 소리 순서로", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.75f))
        }
        Column(Modifier.weight(1f)) {
            WakeStep.entries.forEach { step ->
                val done = step in s.wakeDone
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(if (done) Color.White else Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) { if (done) Icon(Icons.Rounded.Check, null, tint = Palette.Dawn2, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(16.dp))
                    Text(step.label, style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = if (done) 1f else 0.6f))
                }
            }
            Spacer(Modifier.height(20.dp))
            if (s.askingAwake) {
                BigButton("깼어요", Modifier.fillMaxWidth(), height = 76.dp, onClick = a.awake)
                Spacer(Modifier.height(14.dp))
            }
            BigButton("5분 더", Modifier.fillMaxWidth(), primary = false, height = 76.dp, onClick = a.extend)
        }
    }
}

// ---------------- 출발 준비 ----------------

@Composable
private fun Departing(s: UiState, a: Actions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("출발 준비", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Spacer(Modifier.height(18.dp))
            Check("기지개 한 번", true)
            Check("물 한 모금", true)
            val beltText = when (s.seatbelt) {
                true -> "안전벨트 확인됨"
                false -> "안전벨트를 매 주세요"
                null -> "안전벨트 확인"
            }
            Check(beltText, s.seatbelt == true)
        }
        Column(Modifier.weight(1f)) {
            Text("시트·공조·창문·조명을\n쉼표 전으로 되돌렸어요", style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(24.dp))
            BigButton("출발할게요", Modifier.fillMaxWidth(), icon = Icons.Rounded.Navigation, onClick = a.depart)
            Spacer(Modifier.height(14.dp))
            BigButton("5분 더", Modifier.fillMaxWidth(), primary = false, height = 72.dp, onClick = a.extend)
        }
    }
}

@Composable
private fun Check(text: String, ok: Boolean) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (ok) Color.White else Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) { if (ok) Icon(Icons.Rounded.Check, null, tint = Palette.Dawn2, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White)
    }
}

// ---------------- 설정·시연 ----------------

@Composable
private fun Settings(s: UiState, a: Actions, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(520.dp).background(Palette.Panel).clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("설정", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "닫기", tint = Palette.Sub)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("오늘 쉼표 ${s.restsToday}번 · ${s.restMinutesToday}분", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("시연 모드", style = MaterialTheme.typography.titleMedium)
                    Text("3초를 1분으로 줄여 흐름을 빨리 보여 줘요", style = MaterialTheme.typography.bodyMedium, color = Palette.Sub)
                }
                Switch(s.demo, a.setDemo, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Amber))
            }
            if (s.demo) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallAction("운전 +30분") { a.demoAdd(30) }
                    SmallAction("운전 +60분") { a.demoAdd(60) }
                }
            }
            a.simGear?.let { setGear ->
                Spacer(Modifier.height(24.dp))
                Text("가상 차량", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallAction("기어 P") { setGear(Gear.PARK) }
                    SmallAction("기어 D") { setGear(Gear.DRIVE) }
                }
                a.simBelt?.let { setBelt ->
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SmallAction("벨트 풀기") { setBelt(false) }
                        SmallAction("벨트 매기") { setBelt(true) }
                    }
                }
                a.simDoor?.let { setDoor ->
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SmallAction("문 열기") { setDoor(true) }
                        SmallAction("문 닫기") { setDoor(false) }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("차량 기능", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val f = s.features
            listOf(
                "시트 등받이" to f.seatRecline, "시트 통풍" to f.seatVentilation, "공조" to f.climate,
                "창문" to f.window, "실내등" to f.cabinLight, "앰비언트 조명" to f.ambientLight,
                "문 잠금" to f.doorLock, "문 열림 감지" to f.doorPosition, "동승자 감지" to f.seatOccupancy, "안전벨트" to f.seatbelt, "트립 운전 시간" to f.tripTime, "EV 배터리" to f.evBattery,
            ).forEach { (k, on) ->
                Text("${if (on) "●" else "○"}  $k", style = MaterialTheme.typography.bodyMedium, color = if (on) Palette.Mint else Palette.Sub)
            }
            Spacer(Modifier.height(28.dp))
            Text("졸음쉼터 위치: 공공데이터포털 「전국졸음쉼터표준데이터」(한국도로공사, 2026-02-25 기준)",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = Palette.Sub)
        }
    }
}

@Composable
private fun RowScope.SmallAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(16.dp)).background(Palette.PanelHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelMedium) }
}
