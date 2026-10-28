package app.shimpyo.core

import android.content.SharedPreferences
import android.util.Log
import app.shimpyo.location.Fix
import app.shimpyo.location.LocationGateway
import app.shimpyo.navi.NaviGateway
import app.shimpyo.vehicle.CabinFeatures
import app.shimpyo.vehicle.CabinSnapshot
import app.shimpyo.vehicle.Gear
import app.shimpyo.vehicle.SimVehicleGateway
import app.shimpyo.vehicle.VehicleGateway
import app.shimpyo.voice.VoiceGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class Phase { DRIVING, SUGGESTING, HEADING, PARKED, RESTING, WAKING, DEPARTING }

data class UiState(
    val phase: Phase = Phase.DRIVING,
    val gear: Gear = Gear.UNKNOWN,
    val continuousMin: Int = 0,
    val thresholdMin: Int = 120,
    val nextArea: RestAreaHit? = null,
    val target: RestArea? = null,
    val targetDistanceKm: Double? = null,
    val arrived: Boolean = false,
    val restMinutes: Int = 20,
    val restTotalMs: Long = 0,
    val restRemainingMs: Long = 0,
    val applied: List<String> = emptyList(),
    val manualHints: List<String> = emptyList(),
    val wakeDone: Set<WakeStep> = emptySet(),
    val seatbelt: Boolean? = null,
    val beltWasOff: Boolean = false,
    val features: CabinFeatures = CabinFeatures(),
    val vehicleConnected: Boolean = false,
    val naviAvailable: Boolean = false,
    val restricted: Boolean = false,
    val demo: Boolean = false,
    val simulated: Boolean = false,
    val restsToday: Int = 0,
    val restMinutesToday: Int = 0,
    val busy: Boolean = false,
    /** 쉼표 중 문이 열려 시간을 멈춘 상태. */
    val paused: Boolean = false,
    /** 알람 뒤 "깨셨어요?"를 묻는 중. */
    val askingAwake: Boolean = false,
    /** 동승자가 있어 운전석만 맞춘 쉼표. */
    val passengers: Boolean = false,
    /** 핸들 보정 패턴이 운전 초반과 달라져 제안 기준을 앞당겼는지. */
    val patternChanged: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** 충전하며 쉬는 중이면 깨울 충전량(%). */
    val chargeTarget: Int? = null,
    /** 목표 충전량까지 남은 분(어림). 모르면 null. */
    val chargeEtaMin: Int? = null,
)

/** 음성 비서(Gleo) 명령을 처리한 결과. */
sealed class CommandResult {
    data class RestStarted(val minutes: Int) : CommandResult()
    data class Routed(val hit: RestAreaHit) : CommandResult()
    data class Found(val hit: RestAreaHit) : CommandResult()
    data object AlreadyResting : CommandResult()
    data object NoArea : CommandResult()
    data object Waking : CommandResult()
    data object NotResting : CommandResult()
}

/**
 * 쉼표의 흐름 전체: 연속 운전 판단 → 쉼터 제안 → 경유 → 쉼표 모드 → 깨우기 → 출발 확인.
 * 모든 상태 변경은 메인 스레드의 [scope]에서 일어난다.
 */
class ShimpyoController(
    private val scope: CoroutineScope,
    private val vehicle: VehicleGateway,
    private val navi: NaviGateway,
    private val location: LocationGateway,
    val voice: VoiceGateway,
    private val areas: RestAreaRepository,
    private val log: RestLog,
    private val restrictions: DrivingRestrictions?,
    private val prefs: SharedPreferences,
    private val policy: RestPolicy = RestPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(
        UiState(demo = prefs.getBoolean(KEY_DEMO, false), simulated = vehicle is SimVehicleGateway)
    )
    val state: StateFlow<UiState> = _state
    val sim: SimVehicleGateway? = vehicle as? SimVehicleGateway

    private val tracker = ContinuousDrivingTracker(policy)
    private val sessions = SessionStore(prefs)
    private var suggestionsThisTrip = 0
    private var lastDeclineAt: Long? = null
    private var snapshot: CabinSnapshot = CabinSnapshot()
    private var restStartedAt = 0L
    private var restEndAt = 0L
    private var tripBaseline = 0
    private var flowJob: Job? = null
    private val wakeJobs = mutableMapOf<WakeStep, Job>()
    /** 등받이를 젖힌 채인지. 이미 세운 시트를 한 번 더 움직이지 않게 한다. */
    private var seatReclined = false
    /** 문이 열려 멈춘 동안의 남은 시간. */
    private var pausedRemaining: Long? = null
    private var awakeTapped = false
    /** 주행 기어라 등받이를 못 세웠다 — P단이 되면 세운다. */
    private var seatRestorePending = false
    private val steering = SteeringPattern(resetAfterParkedMin = policy.stopResetsAfterMin)
    private var patternLogged = false
    private val chargeEta = ChargeEstimator()
    /** 이번 충전에서 쉬어 가자고 이미 물었는지. */
    private var chargeOffered = false
    private var chargeReached = false

    private val minuteMillis: Long get() = if (_state.value.demo) DEMO_MINUTE_MS else 60_000L

    fun start() {
        vehicle.start(); navi.start(); location.start(); voice.start(); restrictions?.start()
        scope.launch { navi.arrivedAtRestStop.collect { onArrived("navi") } }
        scope.launch { location.arrivals.collect { onArrived("proximity") } }
        scope.launch { vehicle.gear.collect { onGear(it) } }
        scope.launch { vehicle.doorOpen.collect { onDoor(it == true) } }
        scope.launch { vehicle.steeringDeg.collect { it?.let(steering::onAngle) } }
        scope.launch { vehicle.charging.collect { onCharging(it == true) } }
        scope.launch {
            // 앱이 쉼표 도중에 꺼졌다면, 차량 기능 확인이 끝난 뒤 이어 가거나 되돌린다
            awaitReady()
            sessions.load()?.let { resume(it) }
        }
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
        refreshToday()
    }

    fun stop() {
        vehicle.stop(); navi.stop(); location.stop(); voice.stop(); restrictions?.stop()
    }

    /** 차량 기능 확인이 끝날 때까지(최대 [READY_WAIT_MS]). */
    suspend fun awaitReady(timeoutMs: Long = READY_WAIT_MS) {
        withTimeoutOrNull(timeoutMs) { vehicle.ready.first { it } }
    }

    // ---------------- 화면에서 부르는 동작 ----------------

    fun acceptSuggestion() {
        val area = _state.value.target ?: _state.value.nextArea?.area ?: return
        voice.cancelListening()
        flowJob?.cancel()
        goTo(area)
    }

    fun declineSuggestion() {
        voice.cancelListening()
        flowJob?.cancel()
        location.clearArrival()
        lastDeclineAt = clock()
        _state.update { it.copy(phase = Phase.DRIVING, target = null) }
        scope.launch { voice.say("알겠어요. ${policy.retryAfterDeclineMin}분 뒤에 한 번만 더 여쭤볼게요.") }
    }

    /** 주행 중 "지금 쉬어 가기". */
    fun restNow() {
        val hit = _state.value.nextArea
        if (hit == null) {
            scope.launch { voice.say("가까운 졸음쉼터를 찾지 못했어요. 다음 휴게소에서 쉬어 가세요.") }
            return
        }
        flowJob?.cancel()
        goTo(hit.area)
    }

    fun startRest(minutes: Int) {
        val gear = vehicle.gear.value
        if (gear.isDriving) {
            scope.launch { voice.say("P단에 두고 시작할 수 있어요.") }
            return
        }
        voice.cancelListening()
        flowJob?.cancel()
        flowJob = scope.launch { beginRest(minutes) }
    }

    /** 바로 깨우기 단계로. */
    fun wakeNow() {
        if (_state.value.phase == Phase.RESTING || _state.value.phase == Phase.WAKING) {
            pausedRemaining = null
            _state.update { it.copy(paused = false) }
            restEndAt = clock()
        }
    }

    /** 알람 뒤 "깼어요" 버튼. */
    fun confirmAwake() {
        if (!_state.value.askingAwake) return
        awakeTapped = true
        voice.cancelListening()
    }

    fun extendRest(minutes: Int = 5) {
        val phase = _state.value.phase
        if (phase != Phase.WAKING && phase != Phase.DEPARTING) return
        cancelWake()
        voice.cancelListening()
        restEndAt = clock() + minutes * minuteMillis
        _state.update {
            // 남은 시간도 같이 넣어야 다음 tick 전까지 시계가 0:00으로 보이지 않는다
            it.copy(phase = Phase.RESTING, restTotalMs = restEndAt - restStartedAt, restRemainingMs = restEndAt - clock(),
                wakeDone = emptySet(), restMinutes = it.restMinutes + minutes, askingAwake = false)
        }
        persist()
        flowJob = scope.launch {
            voice.say("${minutes}분 더 쉬어요.")
            // 깨우기 도중이면 시트가 아직 젖혀져 있을 수 있다
            if (!seatReclined) {
                seatReclined = true; persist()
                if (!vehicle.reclineDriverSeat()) { seatReclined = false; persist() }
            }
            vehicle.setRestClimate(snapshot)
            vehicle.dimCabin()
        }
    }

    fun depart() {
        if (_state.value.phase == Phase.DEPARTING) finishRest()
    }

    // ---------------- Gleo 음성 명령 ----------------

    /** "쉬어 갈래": 정차 중이면 바로 쉼표, 주행 중이면 앞쪽 쉼터를 경유지로. */
    fun commandStartRest(minutes: Int?): CommandResult {
        val s = _state.value
        val m = (minutes ?: DEFAULT_REST_MIN).coerceIn(10, 30)
        return when {
            s.phase == Phase.RESTING || s.phase == Phase.WAKING -> {
                scope.launch { voice.say("지금 쉼표 중이에요.") }
                CommandResult.AlreadyResting
            }
            vehicle.gear.value.isDriving -> {
                val hit = s.nextArea ?: run {
                    scope.launch { voice.say("앞쪽에 가까운 졸음쉼터를 찾지 못했어요. 다음 휴게소에서 쉬어 가세요.") }
                    return CommandResult.NoArea
                }
                voice.cancelListening()
                flowJob?.cancel()
                goTo(hit.area)
                CommandResult.Routed(hit)
            }
            s.phase == Phase.DEPARTING -> { extendRest(m); CommandResult.RestStarted(m) }
            else -> { startRest(m); CommandResult.RestStarted(m) }
        }
    }

    /** "가까운 졸음쉼터 어디야". */
    fun commandFindArea(): CommandResult {
        val hit = _state.value.nextArea
        scope.launch {
            voice.say(
                if (hit == null) "앞쪽에 가까운 졸음쉼터를 찾지 못했어요."
                else "${hit.distanceKm.roundToInt().coerceAtLeast(1)}킬로미터 앞에 ${hit.area.displayName}가 있어요." +
                    if (hit.area.hasToilet) " 화장실도 있어요." else ""
            )
        }
        return hit?.let { CommandResult.Found(it) } ?: CommandResult.NoArea
    }

    /** "그만 깰래". */
    fun commandEndRest(): CommandResult = when (_state.value.phase) {
        Phase.RESTING, Phase.WAKING -> { wakeNow(); CommandResult.Waking }
        Phase.DEPARTING -> CommandResult.Waking
        else -> {
            scope.launch { voice.say("지금은 쉼표 중이 아니에요.") }
            CommandResult.NotResting
        }
    }

    fun setDemo(on: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, on).apply()
        tracker.reset()
        steering.reset()
        _state.update { it.copy(demo = on) }
    }

    /** 시연 모드에서 연속 운전 시간을 앞당긴다(영상 촬영용). */
    fun demoAddDriving(minutes: Int) {
        if (!_state.value.demo) return
        tracker.seedMinutes(tracker.continuousMinutes(minuteMillis) + minutes, minuteMillis)
        lastDeclineAt = null
        suggestionsThisTrip = 0
    }

    // ---------------- 내부 흐름 ----------------

    private fun tick() {
        val now = clock()
        val s = _state.value
        val gear = vehicle.gear.value
        val mm = minuteMillis
        val resting = s.phase == Phase.RESTING || s.phase == Phase.WAKING || s.phase == Phase.DEPARTING

        if (!resting) {
            tracker.tick(now, gear.isDriving, mm)
            steering.tick(now, gear.isDriving, mm)
        }
        if (!s.demo) vehicle.tripDrivingMinutes.value?.let { tracker.seedMinutes(it - tripBaseline, mm) }
        if (steering.changed && !patternLogged) {
            Log.i(TAG, "steering pattern changed: ${"%.1f".format(steering.baselinePerMin)}/min -> ${"%.1f".format(steering.recentPerMin)}/min")
        }
        patternLogged = steering.changed
        val soc = vehicle.batteryPercent.value
        val charging = vehicle.charging.value == true
        chargeEta.update(now, soc, charging)

        val fix = location.fix.value ?: if (s.demo) DEMO_FIX else null
        val next = fix?.let { areas.nextAhead(it.lat, it.lon, it.headingDeg) }
        val target = s.target
        val targetKm = if (fix != null && target != null) Geo.distanceKm(fix.lat, fix.lon, target.lat, target.lon) else null
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val belt = vehicle.driverSeatbeltBuckled.value

        val remaining = pausedRemaining ?: max(0L, restEndAt - now)
        val arrivedByDistance = s.phase == Phase.HEADING && targetKm != null && targetKm < ARRIVE_KM
        if (arrivedByDistance && !s.arrived) Log.i(TAG, "arrived via distance (${"%.2f".format(targetKm)}km)")
        val ns = s.copy(
            gear = gear,
            continuousMin = tracker.continuousMinutes(mm),
            thresholdMin = policy.thresholdFor(hour, steering.changed),
            patternChanged = steering.changed,
            batteryPercent = soc,
            charging = charging,
            chargeEtaMin = if (charging) chargeEta.minutesTo(s.chargeTarget ?: ChargeRest.TARGET_PCT, mm) else null,
            nextArea = next,
            targetDistanceKm = targetKm,
            arrived = s.arrived || arrivedByDistance,
            // 시트·공조를 맞추는 동안(busy)은 시계를 멈춰 둔다 — 준비가 끝나면 시작 시각을 다시 잡는다
            restRemainingMs = if ((s.phase == Phase.RESTING || s.phase == Phase.WAKING) && !s.busy) remaining else s.restRemainingMs,
            seatbelt = belt,
            beltWasOff = s.beltWasOff || (resting && belt == false),
            features = vehicle.features.value,
            vehicleConnected = vehicle.connected.value,
            naviAvailable = navi.available.value,
            restricted = restrictions?.restricted?.value ?: false,
        )
        // 상태를 먼저 반영하고, 아래 동작들은 _state.update로 그 위에 바꾼다
        _state.value = ns

        when (ns.phase) {
            Phase.DRIVING -> {
                val sinceDecline = lastDeclineAt?.let { ((now - it) / mm).toInt() }
                if (gear.isDriving && flowJob?.isActive != true &&
                    policy.shouldSuggest(ns.continuousMin, hour, suggestionsThisTrip, sinceDecline, steering.changed)
                ) {
                    lastDeclineAt = null
                    suggest(next, ns.continuousMin)
                }
            }
            Phase.RESTING, Phase.WAKING -> if (pausedRemaining == null && !ns.busy) {
                if (!charging) lowBatteryCheck(remaining)
                chargeTargetCheck(soc, remaining)
                val due = WakePlan.dueSteps(remaining, ns.wakeDone, mm)
                if (due.isNotEmpty()) runWake(due)
            }
            Phase.DEPARTING -> {
                if (ns.beltWasOff && belt == true && flowJob?.isActive != true) finishRest()
            }
            else -> Unit
        }
    }

    private fun suggest(hit: RestAreaHit?, continuousMin: Int) {
        suggestionsThisTrip++
        // 졸리다고 단정하지 않고, 본 것(핸들 움직임)만 말한다
        val why = if (steering.changed) "핸들 움직임이 출발 때와 달라졌어요. " else ""
        if (hit == null) {
            flowJob = scope.launch {
                voice.say("$why${durationText(continuousMin)}째 운전 중이에요. 다음 휴게소나 졸음쉼터에서 쉬어 가세요.")
            }
            lastDeclineAt = clock()
            return
        }
        _state.update { it.copy(phase = Phase.SUGGESTING, target = hit.area) }
        flowJob = scope.launch {
            val km = hit.distanceKm.roundToInt().coerceAtLeast(1)
            voice.say("$why${durationText(continuousMin)}째 운전 중이에요. ${km}킬로미터 앞 ${hit.area.displayName}에서 쉬어 갈까요?")
            if (_state.value.phase != Phase.SUGGESTING) return@launch
            when (voice.askYesNo()) {
                true -> acceptSuggestion()
                false -> declineSuggestion()
                null -> {
                    // 대답이 없으면 화면 버튼을 잠시 더 기다렸다가 넘어간다
                    delay(SUGGEST_WAIT_MS)
                    if (_state.value.phase == Phase.SUGGESTING) declineSuggestion()
                }
            }
        }
    }

    private fun goTo(area: RestArea) {
        val routed = navi.goVia(area)
        // 도착 판정: 플랫폼 근접 알림(FusedLocation), 안 되면 tick에서 거리로
        val watching = location.watchArrival(area.lat, area.lon, (ARRIVE_KM * 1000).toFloat())
        Log.i(TAG, "heading to ${area.displayName}, routed=$routed proximity=$watching")
        _state.update { it.copy(phase = Phase.HEADING, target = area, arrived = false) }
        scope.launch {
            voice.say(
                (if (routed) "${Josa.eulReul(area.displayName)} 경유지로 넣었어요." else "${Josa.ro(area.displayName)} 가세요.") +
                    " 도착해서 P단으로 바꾸면 쉼표를 시작할게요. 커피가 있으면 쉬기 직전에 드세요. 깰 때쯤 효과가 나요."
            )
        }
    }

    private fun onArrived(source: String) {
        val s = _state.value
        if (s.phase == Phase.HEADING) {
            if (!s.arrived) Log.i(TAG, "arrived via $source")
            _state.update { it.copy(arrived = true) }
        }
        if (vehicle.gear.value == Gear.PARK) onGear(Gear.PARK)
    }

    /** 쉼표 중 문이 열리면(화장실 등) 시간을 멈추고, 닫히면 남은 시간으로 이어 간다. */
    private fun onDoor(open: Boolean) {
        val s = _state.value
        val now = clock()
        if (open && s.phase == Phase.RESTING && pausedRemaining == null && !s.busy) {
            pausedRemaining = max(0L, restEndAt - now)
            _state.update { it.copy(paused = true) }
            persist()
            Log.i(TAG, "door open: paused with ${pausedRemaining}ms left")
            scope.launch { voice.say("문이 열려서 쉼표를 잠시 멈췄어요. 문을 닫으면 이어 갈게요.") }
        } else if (!open && pausedRemaining != null) {
            val left = pausedRemaining ?: 0L
            pausedRemaining = null
            restEndAt = now + left
            _state.update { it.copy(paused = false) }
            persist()
            Log.i(TAG, "door closed: resumed with ${left}ms left")
            if (s.phase == Phase.RESTING) {
                val min = ceil(left.toDouble() / minuteMillis).toInt().coerceAtLeast(1)
                scope.launch { voice.say("다시 쉼표를 이어 갈게요. ${min}분 남았어요.") }
            }
        }
    }

    /** 정차 중 충전을 시작하면, 충전하는 동안 쉬어 가자고 한 번 묻는다. */
    private fun onCharging(on: Boolean) {
        if (!on) { chargeOffered = false; return }
        val soc = vehicle.batteryPercent.value
        if (chargeOffered || _state.value.phase != Phase.PARKED || (soc != null && soc >= ChargeRest.TARGET_PCT)) return
        chargeOffered = true
        Log.i(TAG, "charging started at $soc%")
        voice.cancelListening()
        flowJob?.cancel()
        flowJob = scope.launch {
            // 1%가 오를 때까지 잠깐 기다리면 목표까지 남은 시간을 어림할 수 있다
            withTimeoutOrNull(CHARGE_RATE_WAIT_MIN * minuteMillis) {
                while (chargeEta.minutesTo(ChargeRest.TARGET_PCT, minuteMillis) == null) delay(TICK_MS)
            }
            val eta = chargeEta.minutesTo(ChargeRest.TARGET_PCT, minuteMillis)
            val minutes = ChargeRest.restMinutes(eta, DEFAULT_REST_MIN)
            Log.i(TAG, "offer charge rest: eta=$eta min -> $minutes min")
            voice.say(
                (eta?.let { "${ChargeRest.TARGET_PCT}%까지 ${it}분쯤 걸려요. " } ?: "") +
                    "충전하는 동안 ${minutes}분 쉬어 갈까요? 그 전에 ${ChargeRest.TARGET_PCT}%가 차면 그때 깨워 드릴게요."
            )
            if (_state.value.phase != Phase.PARKED) return@launch
            if (voice.askYesNo() == true) beginRest(minutes)
        }
    }

    /** 충전하며 쉬는 중 목표 충전량에 닿으면, 남은 시간과 관계없이 지금부터 천천히 깨운다. */
    private fun chargeTargetCheck(soc: Int?, remaining: Long) {
        val target = _state.value.chargeTarget ?: return
        if (chargeReached || soc == null || soc < target) return
        chargeReached = true
        val lead = (WakePlan.leadMin * minuteMillis).toLong()
        if (remaining > lead) restEndAt = clock() + lead
        persist()
        Log.i(TAG, "charge target $target% reached at $soc%: wake in ${minOf(remaining, lead)}ms")
    }

    private fun onGear(gear: Gear) {
        val s = _state.value
        if (gear == Gear.PARK && seatRestorePending) scope.launch { finishPendingSeatRestore() }
        when {
            gear == Gear.PARK && (s.phase == Phase.DRIVING || s.phase == Phase.HEADING) -> {
                val fromHeading = s.phase == Phase.HEADING
                _state.update { it.copy(phase = Phase.PARKED, arrived = it.arrived || fromHeading) }
                if (fromHeading) promptStart()
            }
            gear.isDriving && s.phase == Phase.PARKED -> {
                voice.cancelListening()
                flowJob?.cancel()
                if (s.arrived) location.clearArrival()
                _state.update {
                    it.copy(phase = if (it.target != null && !it.arrived) Phase.HEADING else Phase.DRIVING,
                        target = if (it.arrived) null else it.target, arrived = false)
                }
            }
            gear.isDriving && (s.phase == Phase.RESTING || s.phase == Phase.WAKING) ->
                abortRest("기어가 바뀌어서 쉼표를 멈추고 시트와 공조를 되돌렸어요.")
            gear.isDriving && s.phase == Phase.DEPARTING -> finishRest()
        }
    }

    private fun promptStart() {
        flowJob?.cancel()
        flowJob = scope.launch {
            val where = _state.value.target?.displayName?.let { "${it}에 " } ?: ""
            voice.say("${where}도착했어요. ${DEFAULT_REST_MIN}분 쉼표 시작할까요?")
            if (_state.value.phase != Phase.PARKED) return@launch
            if (voice.askYesNo() == true) beginRest(DEFAULT_REST_MIN)
        }
    }

    private suspend fun beginRest(minutes: Int) {
        val t0 = clock()
        location.clearArrival()
        pausedRemaining = null
        _state.update { it.copy(busy = true, restMinutes = minutes, paused = false) }
        voice.say("${minutes}분 쉼표 시작할게요. 시트와 공기를 맞출게요.")
        val snap = vehicle.snapshot()
        snapshot = snap
        val passengers = vehicle.passengersPresent() == true
        // 충전 중에 시작한 쉼표는 목표 충전량에 닿으면 일찍 깨운다
        val soc = vehicle.batteryPercent.value
        val chargeTarget = ChargeRest.TARGET_PCT.takeIf { vehicle.charging.value == true && (soc == null || soc < it) }
        chargeReached = false
        restStartedAt = clock()
        restEndAt = restStartedAt + minutes * minuteMillis
        // 시트를 움직이기 전에 먼저 저장 — 도중에 앱이 꺼져도 다시 켜질 때 되돌릴 수 있게
        _state.update {
            // 화면이 쉼표로 바뀌는 순간부터 남은 시간을 보여 준다(다음 tick을 기다리면 0:00이 잠깐 보임)
            it.copy(phase = Phase.RESTING, passengers = passengers, restTotalMs = restEndAt - restStartedAt,
                restRemainingMs = restEndAt - restStartedAt, applied = emptyList(), manualHints = emptyList(),
                wakeDone = emptySet(), chargeTarget = chargeTarget)
        }
        seatReclined = true
        persist()

        val applied = mutableListOf<String>()
        val hints = mutableListOf<String>()
        if (vehicle.lockDoors()) applied += "문 잠김 확인"
        if (vehicle.reclineDriverSeat()) applied += "운전석 등받이 젖힘" else { seatReclined = false; hints += "등받이를 편하게 젖혀 주세요" }
        if (vehicle.setRestClimate(snap)) applied += "외기 유입 · 1℃ 낮춤" else hints += "공조를 외기 모드로 바꿔 주세요"
        if (passengers) applied += "동승자가 있어 창문은 그대로"
        else if (vehicle.openWindowSlightly()) applied += "운전석 창문 살짝"
        if (vehicle.dimCabin()) applied += "실내 조명 낮춤"

        restStartedAt = clock()
        restEndAt = restStartedAt + minutes * minuteMillis
        Log.i(TAG, "rest started after ${restStartedAt - t0}ms, passengers=$passengers chargeTarget=$chargeTarget applied=$applied hints=$hints")
        _state.update {
            it.copy(
                phase = Phase.RESTING, restTotalMs = restEndAt - restStartedAt, restRemainingMs = restEndAt - restStartedAt,
                applied = applied, manualHints = hints, wakeDone = emptySet(), busy = false,
                beltWasOff = it.seatbelt == false,
            )
        }
        persist()
        if (passengers) voice.say("동승자가 있어서 창문은 그대로 두고 운전석만 맞췄어요.")
        if (hints.isNotEmpty()) voice.say(hints.joinToString(". ") + ".")
        voice.say(
            if (chargeTarget != null) "푹 쉬세요. ${minutes}분 뒤에, 그 전에 ${chargeTarget}%가 차면 그때 천천히 깨워 드릴게요."
            else "푹 쉬세요. ${minutes}분 뒤에 천천히 깨워 드릴게요."
        )
    }

    /** 단계마다 따로 실행해 서로 막지 않게 한다. 알람만 시트가 다 돌아온 뒤에 울린다. */
    private fun runWake(steps: List<WakeStep>) {
        _state.update { it.copy(phase = Phase.WAKING, wakeDone = it.wakeDone + steps) }
        persist()
        for (step in steps) {
            wakeJobs[step] = scope.launch {
                when (step) {
                    WakeStep.LIGHT -> vehicle.brightenAmbient()
                    WakeStep.AIR -> vehicle.coolBreeze(snapshot)
                    WakeStep.SEAT -> restoreSeatIfReclined()
                    WakeStep.ALARM -> {
                        wakeJobs[WakeStep.SEAT]?.join()
                        askAwake()
                    }
                }
            }
        }
    }

    /** 알람 뒤 깼는지 묻는다. 대답이 없으면 두 번 더 깨우고, "더 잘래"면 5분 더. */
    private suspend fun askAwake() {
        awakeTapped = false
        _state.update { it.copy(askingAwake = true) }
        var answer: WakeAnswer? = null
        for (attempt in 0 until AWAKE_TRIES) {
            // 동승자가 자고 있을 수 있으면 경고음 없이 빛·바람·시트와 음성으로만
            if (!_state.value.passengers) voice.chime()
            voice.say(if (attempt == 0) "일어날 시간이에요. 깨셨으면 '네'라고 말해 주세요." else "일어나실 시간이에요. 깨셨으면 대답해 주세요.")
            if (awakeTapped) { answer = WakeAnswer.AWAKE; break }
            if (!voice.canListen) break
            val heard = voice.listen(AWAKE_LISTEN_MS)
            answer = if (awakeTapped) WakeAnswer.AWAKE else heard?.let(VoiceIntent::parseWake)
            Log.i(TAG, "awake check ${attempt + 1}: heard=$heard -> $answer")
            if (answer != null) break
        }
        _state.update { it.copy(askingAwake = false) }
        if (answer == WakeAnswer.MORE) {
            extendRest(5)
            return
        }
        voice.say((if (answer == WakeAnswer.AWAKE) "좋아요. " else "") + "기지개 한 번 켜고, 물 한 모금 마셔요.")
        enterDeparting()
    }

    /** 젖힌 등받이를 세운다. 차량이 거부하면 false. */
    private suspend fun restoreSeatIfReclined(): Boolean {
        if (!seatReclined) return true
        if (!vehicle.restoreDriverSeat(snapshot)) return false
        seatReclined = false
        persist()
        return true
    }

    private suspend fun finishPendingSeatRestore() {
        if (!seatRestorePending || !restoreSeatIfReclined()) return
        seatRestorePending = false
        sessions.clear()
        voice.say("등받이를 원래대로 세웠어요.")
    }

    private fun cancelWake() {
        wakeJobs.values.forEach { it.cancel() }
        wakeJobs.clear()
    }

    private suspend fun enterDeparting() {
        _state.update { it.copy(phase = Phase.DEPARTING) }
        persist()
        restoreSeatIfReclined()
        vehicle.restoreCabin(snapshot)
        if (_state.value.chargeTarget != null) {
            val soc = vehicle.batteryPercent.value
            val line = listOfNotNull(
                soc?.let { "배터리 ${it}%예요." },
                "충전기를 뽑아 주세요.".takeIf { vehicle.charging.value == true },
            ).joinToString(" ")
            if (line.isNotEmpty()) voice.say(line)
        }
        val belt = vehicle.driverSeatbeltBuckled.value
        voice.say(
            when {
                belt == false -> "안전벨트를 매면 출발할 수 있어요."
                else -> "준비되면 출발 버튼을 눌러 주세요."
            }
        )
    }

    private fun finishRest() {
        val s = _state.value
        if (s.phase != Phase.DEPARTING) return
        flowJob?.cancel()
        flowJob = scope.launch {
            navi.clearRestStop()
            location.clearArrival()
            sessions.clear()
            log.add(RestRecord(restStartedAt, s.restMinutes, s.target?.displayName))
            tracker.reset()
            // 쉬고 난 뒤의 운전을 새 기준으로 다시 배운다
            steering.reset()
            tripBaseline = vehicle.tripDrivingMinutes.value ?: 0
            suggestionsThisTrip = 0
            lastDeclineAt = null
            _state.update {
                it.copy(phase = Phase.DRIVING, target = null, arrived = false, applied = emptyList(),
                    manualHints = emptyList(), wakeDone = emptySet(), beltWasOff = false, passengers = false, chargeTarget = null)
            }
            refreshToday()
            voice.say("출발해도 좋아요. 원래 길로 안내를 이어 갈게요.")
        }
    }

    private fun abortRest(message: String) {
        flowJob?.cancel()
        cancelWake()
        voice.cancelListening()
        pausedRemaining = null
        _state.update {
            it.copy(phase = Phase.DRIVING, applied = emptyList(), wakeDone = emptySet(), target = null,
                paused = false, askingAwake = false, passengers = false, chargeTarget = null)
        }
        navi.clearRestStop()
        location.clearArrival()
        scope.launch {
            val seatOk = restoreSeatIfReclined()
            vehicle.restoreCabin(snapshot)
            if (seatOk) {
                sessions.clear()
                voice.say(message)
            } else {
                // 저장된 쉼표는 지우지 않는다 — 앱이 다시 켜져도 등받이를 세울 수 있게
                seatRestorePending = true
                voice.say("쉼표를 멈췄어요. 등받이가 젖혀져 있어요. P단에서 등받이를 세운 뒤 출발하세요.")
            }
        }
    }

    /** 앱이 다시 켜졌을 때 저장된 쉼표를 이어 가거나, 오래 지났거나 이미 출발했으면 되돌리기만 한다. */
    private suspend fun resume(saved: RestSession) {
        val now = clock()
        snapshot = saved.snapshot
        seatReclined = saved.seatReclined
        restStartedAt = saved.startedAt
        restEndAt = saved.endAt
        val driving = vehicle.gear.value.isDriving
        if (driving || saved.resumeAction(now) == RestSession.Resume.RESTORE) {
            Log.i(TAG, "resume: restore only (saved ${saved.phase}, driving=$driving)")
            val seatOk = restoreSeatIfReclined()
            vehicle.restoreCabin(snapshot)
            log.add(RestRecord(saved.startedAt, saved.minutes, saved.placeName))
            refreshToday()
            if (seatOk) {
                sessions.clear()
                voice.say("지난 쉼표 때 바꾼 시트와 공조를 원래대로 되돌렸어요.")
            } else {
                seatRestorePending = true
                voice.say("지난 쉼표 때 젖힌 등받이가 그대로예요. P단에서 등받이를 세운 뒤 출발하세요.")
            }
            return
        }
        pausedRemaining = saved.pausedRemainingMs
        val area = saved.placeName?.let { n -> areas.areas.firstOrNull { it.displayName == n } }
        Log.i(TAG, "resume: continue ${saved.phase}, ${(saved.endAt - now) / 1000}s left, seatReclined=$seatReclined")
        // 깨우는 중이었으면 남은 단계를 처음부터 다시(시트는 seatReclined로 한 번만 움직인다)
        _state.update {
            it.copy(
                phase = if (saved.phase == Phase.DEPARTING) Phase.DEPARTING else Phase.RESTING,
                target = area, arrived = true, restMinutes = saved.minutes,
                restTotalMs = saved.endAt - saved.startedAt, restRemainingMs = pausedRemaining ?: max(0L, saved.endAt - now),
                applied = saved.applied, manualHints = emptyList(), wakeDone = emptySet(),
                paused = pausedRemaining != null, passengers = saved.passengers, beltWasOff = it.seatbelt == false,
                chargeTarget = saved.chargeTarget,
            )
        }
        chargeReached = false
        if (saved.phase == Phase.DEPARTING) flowJob = scope.launch { enterDeparting() }
        else voice.say("앱이 다시 켜져서 쉼표를 이어 갈게요.")
    }

    private fun persist() {
        val s = _state.value
        if (s.phase != Phase.RESTING && s.phase != Phase.WAKING && s.phase != Phase.DEPARTING) return
        sessions.save(
            RestSession(
                phase = s.phase, startedAt = restStartedAt, endAt = restEndAt, pausedRemainingMs = pausedRemaining,
                minutes = s.restMinutes, seatReclined = seatReclined, passengers = s.passengers,
                placeName = s.target?.displayName, applied = s.applied, savedAt = clock(), snapshot = snapshot,
                chargeTarget = s.chargeTarget,
            )
        )
    }

    private var warnedBattery = false
    private fun lowBatteryCheck(remaining: Long) {
        val soc = vehicle.batteryPercent.value ?: return
        if (soc < LOW_BATTERY && remaining > minuteMillis && !warnedBattery) {
            warnedBattery = true
            restEndAt = clock() + minuteMillis
            scope.launch { voice.say("배터리가 ${soc}%라서 조금 일찍 깨울게요.") }
        }
        if (soc >= LOW_BATTERY) warnedBattery = false
    }

    private fun refreshToday() {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = log.since(midnight)
        _state.update { it.copy(restsToday = today.size, restMinutesToday = today.sumOf { r -> r.minutes }) }
    }

    companion object {
        private const val TAG = "Shimpyo/Flow"
        private const val KEY_DEMO = "demo"
        private const val TICK_MS = 500L
        const val DEFAULT_REST_MIN = 20
        const val READY_WAIT_MS = 40_000L
        private const val AWAKE_TRIES = 3
        private const val AWAKE_LISTEN_MS = 6_000L
        /** 시연 모드: 3초를 1분으로. */
        const val DEMO_MINUTE_MS = 3_000L
        private const val SUGGEST_WAIT_MS = 15_000L
        /** 충전을 시작한 뒤 충전 속도를 보려고 기다리는 시간(분). */
        private const val CHARGE_RATE_WAIT_MIN = 1
        private const val ARRIVE_KM = 0.35
        private const val LOW_BATTERY = 15
        /** 위치를 못 받을 때 시연 모드가 쓰는 위치: 경부고속도로 옥산 졸음쉼터 남쪽 약 8km. */
        val DEMO_FIX = Fix(lat = 36.588, lon = 127.334, headingDeg = 25.0)

        fun durationText(min: Int): String {
            val h = min / 60
            val m = min % 60
            return when {
                h == 0 -> "${m}분"
                m == 0 -> "${h}시간"
                else -> "${h}시간 ${m}분"
            }
        }
    }
}
