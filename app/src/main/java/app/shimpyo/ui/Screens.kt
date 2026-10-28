package app.shimpyo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.shimpyo.core.Phase
import app.shimpyo.core.RestAreaHit
import app.shimpyo.core.ShimpyoController
import app.shimpyo.core.UiState
import app.shimpyo.core.WakeStep
import app.shimpyo.vehicle.CabinFeatures
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
    // 쉼표 중엔 완전한 검정, 깨울 때만 아주 옅게 따뜻해진다
    val bg by animateColorAsState(
        when (s.phase) {
            Phase.RESTING -> Palette.Sleep
            Phase.WAKING -> Color(0xFF15110C)
            else -> Palette.Ink
        },
        tween(1500), label = "bg",
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val wide = maxWidth > maxHeight * 1.3f
        val sleeping = s.phase == Phase.RESTING
        // 좌우 여백: 넓은 차량 화면에서는 글이 가장자리에 붙지 않게 넉넉히(위·본문·안내 줄이 같은 선에서 시작)
        val gutter = if (wide) 112.dp else 56.dp
        Column(Modifier.fillMaxSize().padding(horizontal = gutter, vertical = 22.dp)) {
            TopBar(s, sleeping, onSettings = { showSettings = true })
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                AnimatedContent(
                    targetState = s.phase,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                    transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(200)) },
                    label = "phase",
                ) { phase ->
                    when (phase) {
                        Phase.DRIVING -> Driving(s, wide, a)
                        Phase.SUGGESTING -> Suggest(s, wide, a)
                        Phase.HEADING -> Heading(s, wide)
                        Phase.PARKED -> Parked(s, a)
                        Phase.RESTING -> Resting(s, wide, a)
                        Phase.WAKING -> Waking(s, wide, a)
                        Phase.DEPARTING -> Departing(s, wide, a)
                    }
                }
            }
            VoiceLine(spoken, heard, listening, dim = sleeping,
                hint = if (s.askingAwake) "듣고 있어요 — ‘네’ 또는 ‘5분만 더’" else "듣고 있어요 — ‘응’ 또는 ‘아니’")
        }
        if (showSettings && !s.restricted) Settings(s, a, onClose = { showSettings = false })
    }
}

// ---------------- 공통 ----------------

@Composable
private fun TopBar(s: UiState, sleeping: Boolean, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).alpha(if (sleeping) 0.35f else 1f), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString { append("쉼표"); withStyle(SpanStyle(color = Palette.Accent)) { append(",") } },
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
        )
        Spacer(Modifier.width(36.dp))
        when {
            s.simulated -> Status("가상 차량", Palette.Muted)
            s.vehicleConnected -> Status("차량 연결", Palette.Text)
            else -> Status("차량 연결 중", Palette.Accent)
        }
        if (s.naviAvailable) Status("내비 연동", Palette.Text)
        if (s.demo) Status("시연 · 3초 = 1분", Palette.Accent)
        Spacer(Modifier.weight(1f))
        GearRow(s.gear)
        if (!s.restricted) {
            Spacer(Modifier.width(20.dp))
            Box(
                Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Settings, "설정", tint = Palette.Muted, modifier = Modifier.size(28.dp)) }
        }
    }
}

@Composable
private fun Status(text: String, dot: Color) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
    Spacer(Modifier.width(10.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    Spacer(Modifier.width(28.dp))
}

/** 계기판처럼 P R N D 중 지금 기어만 밝게. */
@Composable
private fun GearRow(g: Gear) {
    val now = when (g) { Gear.PARK -> 'P'; Gear.REVERSE -> 'R'; Gear.NEUTRAL -> 'N'; Gear.DRIVE -> 'D'; Gear.UNKNOWN -> ' ' }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        "PRND".forEach { c ->
            Text(c.toString(), style = Mono.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium),
                color = if (c == now) Palette.Text else Palette.Faint)
        }
    }
}

/** 흰 알약(주 동작) / 어두운 알약(보조 동작). */
@Composable
private fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    height: Dp = 88.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(height).clip(shape)
            .background(if (primary) Palette.Text else Palette.Raised)
            .then(if (primary) Modifier else Modifier.border(1.dp, Palette.Hairline, shape))
            .clickable(onClick = onClick).padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = textStyle, color = if (primary) Palette.OnLight else Palette.Text) }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.clip(shape).background(Palette.Surface).border(1.dp, Palette.Hairline, shape).padding(32.dp), content = content)
}

@Composable
private fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))

@Composable
private fun Label(text: String, color: Color = Palette.Muted) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color)

/** 흰 줄 + 회색 줄로 이어지는 제목. */
@Composable
private fun TwoTone(first: String, second: String?, style: TextStyle = MaterialTheme.typography.displayMedium) {
    Text(
        buildAnnotatedString {
            append(first.keepAll())
            if (second != null) { append("\n"); withStyle(SpanStyle(color = Palette.Muted)) { append(second.keepAll()) } }
        },
        style = style,
    )
}

/** 큰 숫자 + 작은 단위(같은 기준선). */
@Composable
private fun RowScope.Value(value: String, unit: String?, size: TextUnit, color: Color = Palette.Text) {
    Text(value, style = Numeral.copy(fontSize = size, lineHeight = size), color = color, modifier = Modifier.alignByBaseline())
    if (unit != null) {
        Spacer(Modifier.width(10.dp))
        Text(unit, style = MaterialTheme.typography.headlineMedium, color = Palette.Muted, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun BigValue(value: String, unit: String?, size: TextUnit, color: Color = Palette.Text) {
    Row { Value(value, unit, size, color) }
}

/** "1시간 52분"을 숫자는 크게, 단위는 작게. */
@Composable
private fun BigDuration(min: Int, size: TextUnit) {
    val h = min / 60
    val m = min % 60
    Row {
        if (h > 0) { Value("$h", "시간", size); Spacer(Modifier.width(22.dp)) }
        Value("$m", "분", size)
    }
}

@Composable
private fun ProgressLine(ratio: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Palette.Hairline)) {
        Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
private fun PulseDot(color: Color = Palette.Accent, size: Dp = 10.dp) {
    val a by rememberInfiniteTransition(label = "dot").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha",
    )
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color))
}

/** 이름 · 값 한 줄, 아래 1px 선. */
@Composable
private fun DataRow(label: String, value: String, valueColor: Color = Palette.Text, labelColor: Color = Palette.Muted) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
        }
        Hairline()
    }
}

/** 넓은 화면은 좌우, 좁은 화면은 위아래. */
@Composable
private fun Split(
    wide: Boolean,
    leftWeight: Float = 1.15f,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(leftWeight), content = left)
            Column(Modifier.weight(1f), content = right)
        }
    } else {
        Column(Modifier.fillMaxWidth().widthIn(max = 760.dp)) { left(); Spacer(Modifier.height(40.dp)); right() }
    }
}

@Composable
private fun VoiceLine(spoken: String?, heard: String?, listening: Boolean, dim: Boolean, hint: String) {
    Column(Modifier.fillMaxWidth().alpha(if (dim) 0.35f else 1f)) {
        Hairline()
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (listening) {
                PulseDot()
                Spacer(Modifier.width(16.dp))
                Text(heard?.let { "“$it”" } ?: hint, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
            } else {
                Text("안내", style = MaterialTheme.typography.bodyMedium, color = Palette.Faint, modifier = Modifier.width(64.dp))
                Text((spoken ?: "운전 중에는 말로 안내해요.").keepAll(), style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun dur(min: Int) = ShimpyoController.durationText(min)

/** 한글이 음절 중간에서 줄바뀌지 않게(CSS keep-all) 단어 안 글자 사이에 줄바꿈 금지 문자를 넣는다. */
private fun String.keepAll(): String = split(' ').joinToString(" ") { w -> w.toList().joinToString("\u2060") }
private fun km(v: Double) = String.format(Locale.KOREA, "%.1f", v)

// ---------------- 주행 ----------------

@Composable
private fun Driving(s: UiState, wide: Boolean, a: Actions) {
    Split(
        wide,
        left = {
            Label("연속 운전")
            Spacer(Modifier.height(8.dp))
            BigDuration(s.continuousMin, 150.sp)
            Spacer(Modifier.height(32.dp))
            val ratio = s.continuousMin.toFloat() / s.thresholdMin
            ProgressLine(ratio, if (ratio >= 0.9f) Palette.Accent else Palette.Text)
            Spacer(Modifier.height(16.dp))
            val left = (s.thresholdMin - s.continuousMin).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (left > 0) "쉬어 가기 권장까지 ${dur(left)}" else "쉬어 갈 때가 됐어요",
                    style = MaterialTheme.typography.bodyLarge, color = if (left > 0) Palette.Muted else Palette.Accent,
                    modifier = Modifier.weight(1f),
                )
                Text("기준 ${dur(s.thresholdMin)}", style = MaterialTheme.typography.bodyLarge, color = Palette.Faint)
            }
        },
        right = {
            RestAreaCard(s.nextArea, "다음 졸음쉼터")
            Spacer(Modifier.height(16.dp))
            Pill("지금 쉬어 가기", Modifier.fillMaxWidth(), onClick = a.restNow)
        },
    )
}

@Composable
private fun RestAreaCard(hit: RestAreaHit?, title: String) {
    Card(Modifier.fillMaxWidth()) {
        Label(title)
        Spacer(Modifier.height(18.dp))
        if (hit == null) {
            Text("주변 쉼터를 찾는 중", style = MaterialTheme.typography.headlineLarge, color = Palette.Muted)
            return@Card
        }
        Row {
            Text(hit.area.name, style = MaterialTheme.typography.displayMedium, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(12.dp))
            Text("졸음쉼터", style = MaterialTheme.typography.headlineMedium, color = Palette.Muted, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(6.dp))
        Text(hit.area.routeName, style = MaterialTheme.typography.bodyMedium, color = Palette.Faint)
        Spacer(Modifier.height(22.dp))
        BigValue(km(hit.distanceKm), "km", 96.sp)
        Spacer(Modifier.height(24.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        Text(
            listOfNotNull(
                if (hit.area.hasToilet) "화장실" else "화장실 없음",
                hit.area.parkingSpaces.takeIf { it > 0 }?.let { "주차 ${it}면" },
            ).joinToString("   ·   "),
            style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
        )
    }
}

// ---------------- 제안 ----------------

@Composable
private fun Suggest(s: UiState, wide: Boolean, a: Actions) {
    val area = s.target
    Split(
        wide,
        left = {
            TwoTone(
                "${dur(s.continuousMin)}째 운전 중이에요.",
                "${area?.displayName ?: "다음 졸음쉼터"}에서\n쉬어 갈까요?",
            )
            Spacer(Modifier.height(44.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill("쉬어 갈게요", Modifier.weight(1.4f), onClick = a.accept)
                Pill("다음에", Modifier.weight(1f), primary = false, onClick = a.decline)
            }
        },
        right = { RestAreaCard(s.nextArea?.takeIf { it.area == area }, "제안하는 쉼터") },
    )
}

// ---------------- 쉼터로 가는 중 ----------------

@Composable
private fun Heading(s: UiState, wide: Boolean) {
    val area = s.target
    Split(
        wide,
        left = {
            Label(if (s.arrived) "도착" else "쉼터로 가는 중", if (s.arrived) Palette.Accent else Palette.Muted)
            Spacer(Modifier.height(10.dp))
            Row {
                Text(area?.name ?: "졸음쉼터", style = MaterialTheme.typography.displayLarge, modifier = Modifier.alignByBaseline())
                if (area != null) {
                    Spacer(Modifier.width(14.dp))
                    Text("졸음쉼터", style = MaterialTheme.typography.headlineLarge, color = Palette.Muted, modifier = Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(20.dp))
            if (s.arrived) {
                Text("P단으로 바꾸면\n쉼표를 시작해요.", style = MaterialTheme.typography.displayLarge, color = Palette.Muted)
            } else {
                s.targetDistanceKm?.let { BigValue(km(it), "km", 170.sp) }
                Spacer(Modifier.height(20.dp))
                Text("도착해서 P단으로 바꾸면 쉼표를 시작해요.", style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
            }
        },
        right = {
            // 카페인 냅: 커피를 마시고 바로 짧게 자면 깰 때쯤 카페인 효과가 난다
            Card(Modifier.fillMaxWidth()) {
                Text("TIP", style = Mono, color = Palette.Accent)
                Spacer(Modifier.height(16.dp))
                Text("커피가 있으면\n쉬기 직전에 드세요.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(14.dp))
                Text("깰 때쯤 카페인 효과가 나요. 커피와 15분 낮잠을 함께하면 졸음이 가장 많이 줄었다는 연구가 있어요.".keepAll(),
                    style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
            }
        },
    )
}

// ---------------- 정차(P단) ----------------

@Composable
private fun Parked(s: UiState, a: Actions) {
    Column(Modifier.fillMaxWidth()) {
        TwoTone(
            if (s.arrived && s.target != null) "${s.target.displayName}에 도착했어요." else "잠깐 쉬어 갈까요?",
            "얼마나 쉴까요?",
        )
        Spacer(Modifier.height(40.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(15, 20, 25).forEach { m ->
                DurationTile(m, recommended = m == ShimpyoController.DEFAULT_REST_MIN, modifier = Modifier.weight(1f), busy = s.busy) {
                    a.startRest(m)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("고르면 시트 · 공기 · 조명을 맞추고 정한 시간에 깨워 드려요.", style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
        Spacer(Modifier.height(14.dp))
        FeatureLine(s.features)
    }
}

@Composable
private fun DurationTile(min: Int, recommended: Boolean, modifier: Modifier, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val ink = if (recommended) Palette.OnLight else Palette.Text
    Column(
        modifier.height(210.dp).clip(shape)
            .background(if (recommended) Palette.Text else Palette.Surface)
            .border(1.dp, if (recommended) Palette.Text else Palette.Hairline, shape)
            .clickable(enabled = !busy, onClick = onClick).padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(if (recommended) "추천" else " ", style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.55f))
        Row {
            Text("$min", style = Numeral.copy(fontSize = 104.sp, lineHeight = 104.sp), color = ink, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(8.dp))
            Text("분", style = MaterialTheme.typography.headlineMedium, color = ink.copy(alpha = 0.55f), modifier = Modifier.alignByBaseline())
        }
    }
}

/** 이 차에서 쉼표가 직접 움직이는 것(밝게)과 말로 안내만 하는 것(흐리게). */
@Composable
private fun FeatureLine(f: CabinFeatures) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(
            "시트" to f.seatRecline, "공조" to f.climate, "창문" to f.window,
            "조명" to (f.cabinLight || f.ambientLight), "문 잠김" to f.doorLock,
        ).forEach { (name, on) ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) Palette.Text else Palette.Faint))
            Spacer(Modifier.width(10.dp))
            Text(if (on) name else "$name · 안내", style = MaterialTheme.typography.bodyLarge, color = if (on) Palette.Text else Palette.Faint)
            Spacer(Modifier.width(32.dp))
        }
    }
}

// ---------------- 쉼표 중 ----------------

@Composable
private fun Resting(s: UiState, wide: Boolean, a: Actions) {
    // 잠들 화면: 밝기를 낮추고 숫자 하나만 또렷하게
    Box(Modifier.fillMaxWidth().alpha(0.88f)) {
        Split(
            wide,
            leftWeight = 1.3f,
            left = {
                Label(if (s.paused) "문 열림 · 잠시 멈춤" else "쉼표 중", if (s.paused) Palette.Accent else Palette.Muted)
                Text(clock(s.restRemainingMs, s.demo), style = Numeral.copy(fontSize = 210.sp, lineHeight = 220.sp, fontWeight = FontWeight.ExtraLight))
                Spacer(Modifier.height(12.dp))
                val progress = if (s.restTotalMs > 0) 1f - s.restRemainingMs.toFloat() / s.restTotalMs else 0f
                ProgressLine(progress, if (s.paused) Palette.Faint else Palette.Accent)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (s.paused) "문을 닫으면 남은 시간으로 이어 가요." else "깊은 잠에 들기 전에 깨워 개운하게 일어나요.",
                    style = MaterialTheme.typography.bodyLarge, color = Palette.Faint,
                )
            },
            right = {
                Label("맞춘 것", Palette.Faint)
                Spacer(Modifier.height(6.dp))
                s.applied.forEach { DataRow(it, "✓", valueColor = Palette.Muted) }
                s.manualHints.forEach { DataRow(it, "직접", valueColor = Palette.Accent, labelColor = Palette.Text) }
                Spacer(Modifier.height(28.dp))
                Pill("지금 일어날게요", primary = false, height = 76.dp, onClick = a.wakeNow)
            },
        )
    }
}

/** 시연 모드에서는 '분'을 3초로 줄였으므로 화면에는 실제 쉼표 시간으로 환산해 보여 준다. */
private fun clock(remainingMs: Long, demo: Boolean): String {
    val minuteMs = if (demo) ShimpyoController.DEMO_MINUTE_MS else 60_000L
    val totalSec = ((remainingMs * 60_000L / minuteMs) / 1000L).toInt()
    return String.format(Locale.KOREA, "%d:%02d", totalSec / 60, totalSec % 60)
}

// ---------------- 깨우기 ----------------

private val WakeStep.short: String
    get() = when (this) {
        WakeStep.LIGHT -> "빛"; WakeStep.AIR -> "바람"; WakeStep.SEAT -> "자세"; WakeStep.ALARM -> "소리"
    }

private val WakeStep.detail: String
    get() = when (this) {
        WakeStep.LIGHT -> "조명을 서서히"; WakeStep.AIR -> "시원한 바람 · 통풍"; WakeStep.SEAT -> "등받이 원위치"; WakeStep.ALARM -> "알람과 확인"
    }

@Composable
private fun Waking(s: UiState, wide: Boolean, a: Actions) {
    Split(
        wide,
        left = {
            TwoTone("천천히 깨워 드릴게요.", "빛 → 바람 → 자세 → 소리")
            Spacer(Modifier.height(48.dp))
            WakeSteps(s.wakeDone)
        },
        right = {
            if (s.askingAwake) {
                Text("깨셨으면\n‘네’라고 말해 주세요.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(28.dp))
                Pill("깼어요", Modifier.fillMaxWidth(), onClick = a.awake)
                Spacer(Modifier.height(14.dp))
            }
            Pill("5분 더", Modifier.fillMaxWidth(), primary = false, height = 76.dp, onClick = a.extend)
        },
    )
}

/** 네 단계를 가로로: 시작한 단계는 흰 선, 가장 최근에 시작한 단계는 주황 선. */
@Composable
private fun WakeSteps(done: Set<WakeStep>) {
    val current = WakeStep.entries.lastOrNull { it in done }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        WakeStep.entries.forEachIndexed { i, step ->
            val isDone = step in done
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxWidth().height(3.dp).background(
                        when {
                            step == current -> Palette.Accent
                            isDone -> Palette.Text
                            else -> Palette.Hairline
                        }
                    )
                )
                Spacer(Modifier.height(16.dp))
                Text(String.format(Locale.US, "%02d", i + 1), style = Mono, color = Palette.Faint)
                Spacer(Modifier.height(6.dp))
                Text(step.short, style = MaterialTheme.typography.headlineLarge, color = if (isDone) Palette.Text else Palette.Muted)
                Spacer(Modifier.height(4.dp))
                Text(step.detail.keepAll(), style = MaterialTheme.typography.bodyMedium, color = Palette.Faint)
            }
        }
    }
}

// ---------------- 출발 준비 ----------------

@Composable
private fun Departing(s: UiState, wide: Boolean, a: Actions) {
    Split(
        wide,
        left = {
            TwoTone("출발 준비", "시트 · 공조 · 창문 · 조명을\n쉼표 전으로 되돌렸어요.")
            Spacer(Modifier.height(32.dp))
            Hairline()
            DataRow("기지개 한 번", "✓", labelColor = Palette.Text)
            DataRow("물 한 모금", "✓", labelColor = Palette.Text)
            DataRow(
                "안전벨트",
                when (s.seatbelt) { true -> "확인됨"; false -> "매 주세요"; null -> "확인해 주세요" },
                valueColor = if (s.seatbelt == false) Palette.Accent else Palette.Text, labelColor = Palette.Text,
            )
        },
        right = {
            Pill("출발할게요", Modifier.fillMaxWidth(), onClick = a.depart)
            Spacer(Modifier.height(14.dp))
            Pill("5분 더", Modifier.fillMaxWidth(), primary = false, height = 76.dp, onClick = a.extend)
        },
    )
}

// ---------------- 설정·시연 ----------------

@Composable
private fun Settings(s: UiState, a: Actions, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.CenterEnd) {
        Row(Modifier.fillMaxHeight().clickable(enabled = false) {}) {
            Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.Hairline))
            Column(
                Modifier.fillMaxHeight().width(540.dp).background(Palette.Surface)
                    .verticalScroll(rememberScrollState()).padding(36.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("설정", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, "닫기", tint = Palette.Muted)
                    }
                }
                Section("오늘")
                DataRow("쉼표", "${s.restsToday}번 · ${s.restMinutesToday}분")

                Section("시연")
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("시연 모드", style = MaterialTheme.typography.bodyLarge)
                        Text("3초를 1분으로 줄여 흐름을 빨리 보여 줘요", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    }
                    Switch(
                        s.demo, a.setDemo,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.OnLight, checkedTrackColor = Palette.Text, checkedBorderColor = Palette.Text,
                            uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.Raised, uncheckedBorderColor = Palette.HairlineStrong,
                        ),
                    )
                }
                if (s.demo) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SmallPill("운전 +30분") { a.demoAdd(30) }
                        SmallPill("운전 +60분") { a.demoAdd(60) }
                    }
                }
                a.simGear?.let { setGear ->
                    Section("가상 차량")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SmallPill("기어 P") { setGear(Gear.PARK) }
                        SmallPill("기어 D") { setGear(Gear.DRIVE) }
                    }
                    a.simBelt?.let { setBelt ->
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SmallPill("벨트 풀기") { setBelt(false) }
                            SmallPill("벨트 매기") { setBelt(true) }
                        }
                    }
                    a.simDoor?.let { setDoor ->
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SmallPill("문 열기") { setDoor(true) }
                            SmallPill("문 닫기") { setDoor(false) }
                        }
                    }
                }

                Section("이 차에서 쓰는 기능")
                val f = s.features
                listOf(
                    "시트 등받이" to f.seatRecline, "시트 통풍" to f.seatVentilation, "공조" to f.climate,
                    "창문" to f.window, "실내등" to f.cabinLight, "앰비언트 조명" to f.ambientLight,
                    "문 잠금" to f.doorLock, "문 열림 감지" to f.doorPosition, "동승자 감지" to f.seatOccupancy,
                    "안전벨트" to f.seatbelt, "트립 운전 시간" to f.tripTime, "EV 배터리" to f.evBattery,
                ).forEach { (k, on) ->
                    DataRow(k, if (on) "자동" else "안내만", valueColor = if (on) Palette.Text else Palette.Faint)
                }
                Spacer(Modifier.height(28.dp))
                Text("졸음쉼터 위치: 공공데이터포털 「전국졸음쉼터표준데이터」(한국도로공사, 2026-02-25 기준)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp), color = Palette.Faint)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(36.dp))
    Text(title, style = MaterialTheme.typography.bodyMedium, color = Palette.Faint)
    Spacer(Modifier.height(4.dp))
    Hairline()
}

@Composable
private fun RowScope.SmallPill(text: String, onClick: () -> Unit) {
    Pill(text, Modifier.weight(1f), primary = false, height = 60.dp, textStyle = MaterialTheme.typography.titleMedium, onClick = onClick)
}
