package app.shimpyo.core

import android.content.SharedPreferences
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
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
)

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
    private var suggestionsThisTrip = 0
    private var lastDeclineAt: Long? = null
    private var snapshot: CabinSnapshot = CabinSnapshot()
    private var restStartedAt = 0L
    private var restEndAt = 0L
    private var tripBaseline = 0
    private var flowJob: Job? = null
    private val wakeJobs = mutableMapOf<WakeStep, Job>()

    private val minuteMillis: Long get() = if (_state.value.demo) DEMO_MINUTE_MS else 60_000L

    fun start() {
        vehicle.start(); navi.start(); location.start(); voice.start(); restrictions?.start()
        scope.launch { navi.arrivedAtRestStop.collect { onArrived() } }
        scope.launch { vehicle.gear.collect { onGear(it) } }
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
        if (_state.value.phase == Phase.RESTING || _state.value.phase == Phase.WAKING) restEndAt = clock()
    }

    fun extendRest(minutes: Int = 5) {
        val phase = _state.value.phase
        if (phase != Phase.WAKING && phase != Phase.DEPARTING) return
        cancelWake()
        restEndAt = clock() + minutes * minuteMillis
        _state.update {
            it.copy(phase = Phase.RESTING, restTotalMs = restEndAt - restStartedAt, wakeDone = emptySet(),
                restMinutes = it.restMinutes + minutes)
        }
        flowJob = scope.launch {
            voice.say("${minutes}분 더 쉬어요.")
            vehicle.reclineDriverSeat()
            vehicle.setRestClimate(snapshot)
            vehicle.dimCabin()
        }
    }

    fun depart() {
        if (_state.value.phase == Phase.DEPARTING) finishRest()
    }

    fun setDemo(on: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, on).apply()
        tracker.reset()
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

        if (!resting) tracker.tick(now, gear.isDriving, mm)
        if (!s.demo) vehicle.tripDrivingMinutes.value?.let { tracker.seedMinutes(it - tripBaseline, mm) }

        val fix = location.fix.value ?: if (s.demo) DEMO_FIX else null
        val next = fix?.let { areas.nextAhead(it.lat, it.lon, it.headingDeg) }
        val target = s.target
        val targetKm = if (fix != null && target != null) Geo.distanceKm(fix.lat, fix.lon, target.lat, target.lon) else null
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val belt = vehicle.driverSeatbeltBuckled.value

        val remaining = max(0L, restEndAt - now)
        val ns = s.copy(
            gear = gear,
            continuousMin = tracker.continuousMinutes(mm),
            thresholdMin = policy.thresholdFor(hour),
            nextArea = next,
            targetDistanceKm = targetKm,
            arrived = s.arrived || (s.phase == Phase.HEADING && targetKm != null && targetKm < ARRIVE_KM),
            restRemainingMs = if (s.phase == Phase.RESTING || s.phase == Phase.WAKING) remaining else s.restRemainingMs,
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
                    policy.shouldSuggest(ns.continuousMin, hour, suggestionsThisTrip, sinceDecline)
                ) {
                    lastDeclineAt = null
                    suggest(next, ns.continuousMin)
                }
            }
            Phase.RESTING, Phase.WAKING -> {
                lowBatteryCheck(remaining)
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
        if (hit == null) {
            flowJob = scope.launch {
                voice.say("${durationText(continuousMin)}째 운전 중이에요. 다음 휴게소나 졸음쉼터에서 쉬어 가세요.")
            }
            lastDeclineAt = clock()
            return
        }
        _state.update { it.copy(phase = Phase.SUGGESTING, target = hit.area) }
        flowJob = scope.launch {
            val km = hit.distanceKm.roundToInt().coerceAtLeast(1)
            voice.say("${durationText(continuousMin)}째 운전 중이에요. ${km}킬로미터 앞 ${hit.area.displayName}에서 쉬어 갈까요?")
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
        _state.update { it.copy(phase = Phase.HEADING, target = area, arrived = false) }
        scope.launch {
            voice.say(
                if (routed) "${area.displayName}을 경유지로 넣었어요. 도착해서 P단으로 바꾸면 쉼표를 시작할게요."
                else "${area.displayName}로 가세요. 도착해서 P단으로 바꾸면 쉼표를 시작할게요."
            )
        }
    }

    private fun onArrived() {
        if (_state.value.phase == Phase.HEADING) _state.update { it.copy(arrived = true) }
        if (vehicle.gear.value == Gear.PARK) onGear(Gear.PARK)
    }

    private fun onGear(gear: Gear) {
        val s = _state.value
        when {
            gear == Gear.PARK && (s.phase == Phase.DRIVING || s.phase == Phase.HEADING) -> {
                val fromHeading = s.phase == Phase.HEADING
                _state.update { it.copy(phase = Phase.PARKED, arrived = it.arrived || fromHeading) }
                if (fromHeading) promptStart()
            }
            gear.isDriving && s.phase == Phase.PARKED -> {
                voice.cancelListening()
                flowJob?.cancel()
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
            voice.say("${where}도착했어요. 20분 쉼표 시작할까요?")
            if (_state.value.phase != Phase.PARKED) return@launch
            if (voice.askYesNo() == true) beginRest(20)
        }
    }

    private suspend fun beginRest(minutes: Int) {
        val t0 = clock()
        _state.update { it.copy(busy = true, restMinutes = minutes) }
        voice.say("${minutes}분 쉼표 시작할게요. 시트와 공기를 맞출게요.")
        val snap = vehicle.snapshot()
        snapshot = snap
        val applied = mutableListOf<String>()
        val hints = mutableListOf<String>()
        if (vehicle.lockDoors()) applied += "문 잠김 확인"
        if (vehicle.reclineDriverSeat()) applied += "운전석 등받이 젖힘" else hints += "등받이를 편하게 젖혀 주세요"
        if (vehicle.setRestClimate(snap)) applied += "외기 유입 · 1℃ 낮춤" else hints += "공조를 외기 모드로 바꿔 주세요"
        if (vehicle.openWindowSlightly()) applied += "운전석 창문 살짝"
        if (vehicle.dimCabin()) applied += "실내 조명 낮춤"

        restStartedAt = clock()
        restEndAt = restStartedAt + minutes * minuteMillis
        android.util.Log.i("Shimpyo/Flow", "rest started after ${restStartedAt - t0}ms, applied=$applied hints=$hints")
        _state.update {
            it.copy(
                phase = Phase.RESTING, restTotalMs = restEndAt - restStartedAt, restRemainingMs = restEndAt - restStartedAt,
                applied = applied, manualHints = hints, wakeDone = emptySet(), busy = false,
                beltWasOff = it.seatbelt == false,
            )
        }
        if (hints.isNotEmpty()) voice.say(hints.joinToString(". ") + ".")
        voice.say("푹 쉬세요. ${minutes}분 뒤에 천천히 깨워 드릴게요.")
    }

    /** 단계마다 따로 실행해 서로 막지 않게 한다. 알람만 시트가 다 돌아온 뒤에 울린다. */
    private fun runWake(steps: List<WakeStep>) {
        _state.update { it.copy(phase = Phase.WAKING, wakeDone = it.wakeDone + steps) }
        for (step in steps) {
            wakeJobs[step] = scope.launch {
                when (step) {
                    WakeStep.LIGHT -> vehicle.brightenAmbient()
                    WakeStep.AIR -> vehicle.coolBreeze(snapshot)
                    WakeStep.SEAT -> vehicle.restoreDriverSeat(snapshot)
                    WakeStep.ALARM -> {
                        wakeJobs[WakeStep.SEAT]?.join()
                        voice.chime()
                        voice.say("일어날 시간이에요. 기지개 한 번 켜고, 물 한 모금 마셔요.")
                        enterDeparting()
                    }
                }
            }
        }
    }

    private fun cancelWake() {
        wakeJobs.values.forEach { it.cancel() }
        wakeJobs.clear()
    }

    private suspend fun enterDeparting() {
        _state.update { it.copy(phase = Phase.DEPARTING) }
        vehicle.restoreCabin(snapshot)
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
            log.add(RestRecord(restStartedAt, s.restMinutes, s.target?.displayName))
            tracker.reset()
            tripBaseline = vehicle.tripDrivingMinutes.value ?: 0
            suggestionsThisTrip = 0
            lastDeclineAt = null
            _state.update {
                it.copy(phase = Phase.DRIVING, target = null, arrived = false, applied = emptyList(),
                    manualHints = emptyList(), wakeDone = emptySet(), beltWasOff = false)
            }
            refreshToday()
            voice.say("출발해도 좋아요. 원래 길로 안내를 이어 갈게요.")
        }
    }

    private fun abortRest(message: String) {
        flowJob?.cancel()
        cancelWake()
        _state.update { it.copy(phase = Phase.DRIVING, applied = emptyList(), wakeDone = emptySet(), target = null) }
        navi.clearRestStop()
        scope.launch {
            vehicle.restoreDriverSeat(snapshot)
            vehicle.restoreCabin(snapshot)
            voice.say(message)
        }
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
        private const val KEY_DEMO = "demo"
        private const val TICK_MS = 500L
        /** 시연 모드: 3초를 1분으로. */
        const val DEMO_MINUTE_MS = 3_000L
        private const val SUGGEST_WAIT_MS = 15_000L
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
