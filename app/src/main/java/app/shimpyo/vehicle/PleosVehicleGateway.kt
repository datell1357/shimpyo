package app.shimpyo.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.sign

/**
 * Pleos Connect Vehicle SDK로 시트·공조·창문·조명·문·안전벨트를 다룬다.
 * 모든 호출은 차종별 capability 확인 뒤에만 하고, 실패해도 앱 흐름은 계속된다.
 */
class PleosVehicleGateway(context: Context) : VehicleGateway {

    private val vehicle = Vehicle(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var probeJob: Job? = null

    private val _connected = MutableStateFlow(false)
    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    private val _trip = MutableStateFlow<Int?>(null)
    private val _belt = MutableStateFlow<Boolean?>(null)
    private val _battery = MutableStateFlow<Int?>(null)
    private val _features = MutableStateFlow(CabinFeatures())

    override val connected: StateFlow<Boolean> = _connected
    override val gear: StateFlow<Gear> = _gear
    override val tripDrivingMinutes: StateFlow<Int?> = _trip
    override val driverSeatbeltBuckled: StateFlow<Boolean?> = _belt
    override val batteryPercent: StateFlow<Int?> = _battery
    override val features: StateFlow<CabinFeatures> = _features

    // capability 확인으로 고른 영역과 값 범위
    private var seatArea: SeatArea? = null
    // 공조는 속성마다 지원 영역이 다르다(온도는 운전석, 전원·외기·풍량은 좌석 묶음 등)
    private var tempArea: SeatArea? = null
    private var powerArea: SeatArea? = null
    private var recircArea: SeatArea? = null
    private var fanArea: SeatArea? = null
    private var ventArea: SeatArea? = null
    private var ambientArea: SeatArea? = null
    private var windowArea: WindowArea? = null
    private var doorAreas: List<DoorArea> = emptyList()
    private var occupancyAreas: List<SeatArea> = emptyList()
    private var tempBounds: AreaBounds<Float>? = null
    private var fanBounds: AreaBounds<Int>? = null
    private var ventBounds: AreaBounds<Int>? = null
    private var ambientBounds: AreaBounds<Int>? = null
    private var windowBounds: AreaBounds<Int>? = null
    private var reclinedPosition: Int? = null

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            _connected.value = true
            _gear.value = gear.toGear()
        }
        override fun onFailed(e: Exception) = warn("gear listener", e)
    }
    private val tripListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) { _trip.value = value }
        override fun onFailed(e: Exception) = warn("trip listener", e)
    }
    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(state: Boolean?) { _belt.value = state }
        override fun onFailed(e: Exception) = warn("seatbelt listener", e)
    }
    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) { _battery.value = value }
        override fun onFailed(e: Exception) = warn("battery listener", e)
    }

    override fun start() {
        runCatching { vehicle.initialize() }.onFailure { warn("initialize", it) }
        probeJob = scope.launch {
            // initialize()가 돌아와도 Vehicle Service 연결은 조금 뒤에 끝날 수 있어 몇 번 다시 확인한다
            repeat(15) {
                if (probe()) return@launch
                delay(2_000)
            }
        }
    }

    override fun stop() {
        runCatching {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getOdometer().unregisterTripCurrentDrivingTime(tripListener)
            seatArea?.let { vehicle.getSeat().unregisterSeatBeltBuckledState(it, beltListener) }
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(batteryListener)
        }
        scope.cancel()
        runCatching { vehicle.release() }
    }

    /** 기능 지원 여부를 확인하고 리스너를 건다. 차량 서비스에 닿으면 true. */
    private suspend fun probe(): Boolean {
        val gearNow = call<VehicleGear?> { ok, fail -> vehicle.getGear().getCurrentGearSelectionState(ok, fail) }
        if (gearNow == null && !_connected.value) return false
        _connected.value = true
        _gear.value = gearNow.toGear()
        runCatching { vehicle.getGear().registerGearSelection(gearListener) }

        val seat = vehicle.getSeat()
        val hvac = vehicle.getHVAC()
        val window = vehicle.getWindow()
        val light = vehicle.getLight()
        val door = vehicle.getDoor()
        val odometer = vehicle.getOdometer()

        val reclineAreas = call<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            seat.checkMoveSeatBackrestAngleCapabilities(
                onComplete = { c: CapabilityConfig, a: List<SeatArea> -> ok(c to a) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second
        seatArea = reclineAreas?.driver()

        val vent = call<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
            seat.checkSeatVentilationCapabilities(
                onComplete = { c: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> -> ok(c to m) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second
        ventArea = vent?.keys?.toList()?.driver()
        ventBounds = ventArea?.let { vent?.get(it) }

        val beltAreas = call<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            seat.checkSeatBeltBuckledCapabilities(
                onComplete = { c: CapabilityConfig, a: List<SeatArea> -> ok(c to a) }, onFailed = fail)
        }?.takeIf { it.first.readable }?.second
        val beltArea = beltAreas?.driver()

        occupancyAreas = call<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            seat.checkSeatOccupancyCapabilities(
                onComplete = { c: CapabilityConfig, a: List<SeatArea> -> ok(c to a) }, onFailed = fail)
        }?.takeIf { it.first.readable }?.second.orEmpty()

        val temp = call<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities(
                onComplete = { c: CapabilityConfig, m: Map<SeatArea, AreaBounds<Float>> -> ok(c to m) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second
        tempArea = temp?.keys?.toList()?.driver()
        tempBounds = tempArea?.let { temp?.get(it) }
        val fan = call<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities(
                onComplete = { c: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> -> ok(c to m) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second
        fanArea = fan?.keys?.toList()?.driver()
        fanBounds = fanArea?.let { fan?.get(it) }
        powerArea = call<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            hvac.checkHvacPowerCapabilities(
                onComplete = { c: CapabilityConfig, a: List<SeatArea> -> ok(c to a) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second?.driver()
        recircArea = call<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            hvac.checkHvacRecirculationCapabilities(
                onComplete = { c: CapabilityConfig, a: List<SeatArea> -> ok(c to a) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second?.driver()
        Log.i(TAG, "hvac areas temp=${tempArea.ids()} power=${powerArea.ids()} recirc=${recircArea.ids()} fan=${fanArea.ids()}")

        val win = call<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { ok, fail ->
            window.checkWindowPositionCapabilities(
                onComplete = { c: CapabilityConfig, m: Map<WindowArea, AreaBounds<Int>> -> ok(c to m) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second
        windowArea = win?.keys?.firstOrNull { WindowValue.ROW_1_LEFT in it.values }
        windowBounds = windowArea?.let { win?.get(it) }

        val cabin = call<Pair<CapabilityConfig, List<LightSwitch>>> { ok, fail ->
            light.checkCabinLightSwitchCapabilities(
                onComplete = { c: CapabilityConfig, l: List<LightSwitch> -> ok(c to l) }, onFailed = fail)
        }?.takeIf { it.first.writable && LightSwitch.OFF in it.second }
        val ambient = call<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities(
                onComplete = { c: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> -> ok(c to m) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second
        ambientArea = ambient?.keys?.toList()?.driver()
        ambientBounds = ambientArea?.let { ambient?.get(it) }

        doorAreas = call<Pair<CapabilityConfig, List<DoorArea>>> { ok, fail ->
            door.checkDoorLockCapabilities(
                onComplete = { c: CapabilityConfig, a: List<DoorArea> -> ok(c to a) }, onFailed = fail)
        }?.takeIf { it.first.writable }?.second.orEmpty()

        val ev = call<CapabilityConfig> { ok, fail ->
            vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, fail)
        }?.readable == true
        val trip = call<CapabilityConfig> { ok, fail ->
            odometer.checkTripCurrentDrivingTimeCapabilities(ok, fail)
        }?.readable == true

        _features.value = CabinFeatures(
            seatRecline = seatArea != null,
            seatVentilation = ventArea != null && ventBounds != null,
            seatbelt = beltArea != null,
            seatOccupancy = occupancyAreas.isNotEmpty(),
            climate = tempArea != null && tempBounds != null,
            window = windowArea != null && windowBounds != null,
            cabinLight = cabin != null,
            ambientLight = ambientArea != null && ambientBounds != null,
            doorLock = doorAreas.isNotEmpty(),
            evBattery = ev,
            tripTime = trip,
        )
        Log.i(TAG, "features ${_features.value}")

        if (trip) {
            _trip.value = call<Int?> { ok, fail -> odometer.getTripCurrentDrivingTime(ok, fail) }
            runCatching { odometer.registerTripCurrentDrivingTime(tripListener) }
        }
        if (beltArea != null) {
            _belt.value = call<Boolean?> { ok, fail -> seat.getCurrentSeatBeltBuckledState(beltArea, ok, fail) }
            runCatching { seat.registerSeatBeltBuckledState(beltArea, beltListener) }
        }
        if (ev) {
            _battery.value = call<Int?> { ok, fail -> vehicle.getEvBattery().getEvBatteryStateOfCharge(ok, fail) }
            runCatching { vehicle.getEvBattery().registerEvBatteryStateOfCharge(batteryListener) }
        }
        return true
    }

    override suspend fun passengersPresent(): Boolean? {
        val others = occupancyAreas.filter { SeatValue.ROW_1_LEFT !in it.values }
        if (others.isEmpty()) return null
        val seat = vehicle.getSeat()
        val states = others.map { area -> call<Boolean?> { ok, fail -> seat.getCurrentSeatOccupancy(area, ok, fail) } }
        if (states.all { it == null }) return null
        return states.any { it == true }
    }

    override suspend fun snapshot(): CabinSnapshot {
        val seat = vehicle.getSeat()
        val hvac = vehicle.getHVAC()
        return CabinSnapshot(
            backrest = seatArea?.let { a -> call<Int?> { ok, fail -> seat.getCurrentSeatBackrestAnglePosition(a, ok, fail) } },
            climatePower = powerArea?.let { a -> call<Boolean?> { ok, fail -> hvac.getHvacPowerState(a, ok, fail) } },
            temperature = tempArea?.let { a ->
                call<Float?> { ok, fail -> hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS, ok, fail) }
            },
            fanSpeed = fanArea?.let { a -> call<Int?> { ok, fail -> hvac.getHvacFanSpeed(a, ok, fail) } },
            recirculation = recircArea?.let { a -> call<Boolean?> { ok, fail -> hvac.getHvacRecirculationState(a, ok, fail) } },
            window = windowArea?.let { a ->
                call<Int?> { ok, fail -> vehicle.getWindow().getCurrentWindowPosition(a, ok, fail) }
            },
            cabinLightSwitch = if (_features.value.cabinLight) {
                call<LightSwitch?> { ok, fail -> vehicle.getLight().getCabinLightSwitch(ok, fail) }?.name
            } else null,
            ambientBrightness = ambientArea?.let { a ->
                call<Int?> { ok, fail -> vehicle.getLight().getAmbientLightBrightness(a, ok, fail) }
            },
            ventilation = ventArea?.let { a -> call<Int?> { ok, fail -> seat.getHvacSeatVentilationState(a, ok, fail) } },
        )
    }

    override suspend fun lockDoors(): Boolean {
        if (doorAreas.isEmpty()) return false
        val door = vehicle.getDoor()
        val front = doorAreas.filter { a -> a.values.any { it == DoorValue.ROW_1_LEFT || it == DoorValue.ROW_1_RIGHT } }
            .ifEmpty { doorAreas }
        front.forEach { area ->
            val locked = call<Boolean?> { ok, fail -> door.getDoorLockState(area, ok, fail) }
            if (locked != true) setCall { ok, fail -> door.setDoorLock(area, true, { _, _ -> ok() }, fail) }
        }
        return true
    }

    override suspend fun reclineDriverSeat(): Boolean {
        val area = seatArea ?: return false
        val seat = vehicle.getSeat()
        val start = call<Int?> { o, f -> seat.getCurrentSeatBackrestAnglePosition(area, o, f) }
        val ok = setCall { done, fail -> seat.moveSeatBackrestAngle(area, MoveControl.BACKWARD, { _, _ -> done() }, fail) }
        if (!ok) return false
        delay(RECLINE_MS)
        setCall { done, fail -> seat.moveSeatBackrestAngle(area, MoveControl.STOP, { _, _ -> done() }, fail) }
        reclinedPosition = call<Int?> { o, f -> seat.getCurrentSeatBackrestAnglePosition(area, o, f) }
        Log.i(TAG, "recline ${area.ids()}: $start -> $reclinedPosition")
        return true
    }

    override suspend fun restoreDriverSeat(before: CabinSnapshot): Boolean {
        val area = seatArea ?: return false
        val seat = vehicle.getSeat()
        val target = before.backrest
        val reclined = reclinedPosition
        setCall { done, fail -> seat.moveSeatBackrestAngle(area, MoveControl.FORWARD, { _, _ -> done() }, fail) }
        if (target == null || reclined == null || reclined == target) {
            delay(RECLINE_MS) // 위치 값을 못 읽으면 젖힌 시간만큼 되돌린다
        } else {
            // 젖힐 때 값이 바뀐 방향의 반대로 목표값을 지날 때까지
            val dir = (reclined - target).sign
            val deadline = System.currentTimeMillis() + RECLINE_MS * 2
            while (System.currentTimeMillis() < deadline) {
                delay(250)
                val pos = call<Int?> { o, f -> seat.getCurrentSeatBackrestAnglePosition(area, o, f) } ?: continue
                if ((pos - target).sign != dir) break
            }
        }
        setCall { done, fail -> seat.moveSeatBackrestAngle(area, MoveControl.STOP, { _, _ -> done() }, fail) }
        reclinedPosition = null
        return true
    }

    override suspend fun setRestClimate(before: CabinSnapshot): Boolean {
        val area = tempArea ?: return false
        val hvac = vehicle.getHVAC()
        powerArea?.let { a -> setCall { done, fail -> hvac.setHvacPower(a, true, { _, _ -> done() }, fail) } }
        recircArea?.let { a -> setCall { done, fail -> hvac.setHvacRecirculation(a, false, { _, _ -> done() }, fail) } } // 외기 유입
        val t = clamp((before.temperature ?: DEFAULT_TEMP) - 1f, tempBounds)
        val ok = setCall { done, fail -> hvac.setHvacTemperature(area, t, TemperatureUnit.CELSIUS, { _, _ -> done() }, fail) }
        val fa = fanArea
        val fb = fanBounds
        if (fa != null && fb != null) {
            val fan = (fb.minValue + 2).coerceAtMost(fb.maxValue) // 약하게
            setCall { done, fail -> hvac.setHvacFanSpeed(fa, fan, { _, _ -> done() }, fail) }
        }
        return ok
    }

    override suspend fun openWindowSlightly(): Boolean {
        val area = windowArea ?: return false
        val b = windowBounds ?: return false
        val target = (b.minValue + max(1, (b.maxValue - b.minValue) / 10)).coerceAtMost(b.maxValue)
        return setCall { done, fail -> vehicle.getWindow().setWindowPosition(area, target, { _, _ -> done() }, fail) }
    }

    override suspend fun dimCabin(): Boolean {
        val light = vehicle.getLight()
        var any = false
        if (_features.value.cabinLight) {
            any = setCall { done, fail -> light.setCabinLightSwitch(LightSwitch.OFF, { done() }, fail) } || any
        }
        val area = ambientArea
        val b = ambientBounds
        if (area != null && b != null) {
            val low = b.minValue + (b.maxValue - b.minValue) / 10
            any = setCall { done, fail -> light.setAmbientLightBrightness(area, low, { _, _ -> done() }, fail) } || any
        }
        return any
    }

    override suspend fun brightenAmbient(): Boolean {
        val area = ambientArea ?: return false
        val b = ambientBounds ?: return false
        val light = vehicle.getLight()
        val span = b.maxValue - b.minValue
        for (pct in listOf(30, 55, 80)) {
            setCall { done, fail -> light.setAmbientLightBrightness(area, b.minValue + span * pct / 100, { _, _ -> done() }, fail) }
            delay(700)
        }
        return true
    }

    override suspend fun coolBreeze(before: CabinSnapshot): Boolean {
        var any = false
        val hvac = vehicle.getHVAC()
        tempArea?.let { area ->
            val t = clamp((before.temperature ?: DEFAULT_TEMP) - 2f, tempBounds)
            any = setCall { done, fail -> hvac.setHvacTemperature(area, t, TemperatureUnit.CELSIUS, { _, _ -> done() }, fail) }
        }
        val fa = fanArea
        val fb = fanBounds
        if (fa != null && fb != null) {
            val fan = fb.minValue + (fb.maxValue - fb.minValue) * 2 / 3 // 시원한 바람
            any = setCall { done, fail -> hvac.setHvacFanSpeed(fa, fan, { _, _ -> done() }, fail) } || any
        }
        val va = ventArea
        val vb = ventBounds
        if (va != null && vb != null) {
            val level = (vb.minValue + (vb.maxValue - vb.minValue + 1) / 2).coerceIn(vb.minValue, vb.maxValue)
            any = setCall { done, fail -> vehicle.getSeat().setHvacSeatVentilation(va, level, { _, _ -> done() }, fail) } || any
        }
        return any
    }

    override suspend fun restoreCabin(before: CabinSnapshot) {
        val hvac = vehicle.getHVAC()
        val results = mutableListOf<String>()
        tempArea?.let { area ->
            before.temperature?.let { t ->
                results += "temp $t=" + setCall { done, fail -> hvac.setHvacTemperature(area, t, TemperatureUnit.CELSIUS, { _, _ -> done() }, fail) }
            }
        }
        fanArea?.let { a -> before.fanSpeed?.let { f -> results += "fan $f=" + setCall { done, fail -> hvac.setHvacFanSpeed(a, f, { _, _ -> done() }, fail) } } }
        recircArea?.let { a -> before.recirculation?.let { r -> results += "recirc $r=" + setCall { done, fail -> hvac.setHvacRecirculation(a, r, { _, _ -> done() }, fail) } } }
        powerArea?.let { a -> before.climatePower?.let { p -> results += "power $p=" + setCall { done, fail -> hvac.setHvacPower(a, p, { _, _ -> done() }, fail) } } }
        Log.i(TAG, "restore hvac $results")
        val wa = windowArea
        val wb = windowBounds
        if (wa != null && wb != null) {
            val back = before.window ?: wb.minValue
            setCall { done, fail -> vehicle.getWindow().setWindowPosition(wa, back, { _, _ -> done() }, fail) }
        }
        before.cabinLightSwitch?.let { name ->
            val sw = runCatching { LightSwitch.valueOf(name) }.getOrNull() ?: return@let
            setCall { done, fail -> vehicle.getLight().setCabinLightSwitch(sw, { done() }, fail) }
        }
        val aa = ambientArea
        val ab = before.ambientBrightness
        if (aa != null && ab != null) {
            setCall { done, fail -> vehicle.getLight().setAmbientLightBrightness(aa, ab, { _, _ -> done() }, fail) }
        }
        val va = ventArea
        val v = before.ventilation
        if (va != null && v != null) {
            setCall { done, fail -> vehicle.getSeat().setHvacSeatVentilation(va, v, { _, _ -> done() }, fail) }
        }
    }

    // ---- 도우미 ----

    /** 비동기 Get/Capability 호출을 코루틴으로. 실패·시간 초과는 null. */
    private suspend fun <T> call(
        timeoutMs: Long = CALL_TIMEOUT_MS,
        block: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): T? {
        var answered = false
        val r = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<T?> { cont ->
                try {
                    block(
                        { v -> answered = true; if (cont.isActive) cont.resume(v) },
                        { e -> answered = true; warn("call", e); if (cont.isActive) cont.resume(null) },
                    )
                } catch (t: Throwable) {
                    answered = true
                    warn("call threw", t)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (!answered && timeoutMs >= CALL_TIMEOUT_MS) {
            Log.w(TAG, "call timeout at ${Throwable().stackTrace.getOrNull(1)?.lineNumber}")
        }
        return r
    }

    /**
     * Set 호출. 완료 콜백이 오지 않는 속성도 있어서, 짧게 기다리는 동안 실패 콜백이 없으면 성공으로 본다.
     */
    private suspend fun setCall(block: (done: () -> Unit, fail: (Exception) -> Unit) -> Unit): Boolean {
        var failed = false
        val done = call<Boolean>(SET_WAIT_MS) { ok, fail ->
            block({ ok(true) }, { e -> failed = true; fail(e) })
        }
        return done == true || !failed
    }

    private fun SeatArea?.ids(): String = this?.values?.joinToString("+") { it.name } ?: "-"

    private fun List<SeatArea>.driver(): SeatArea? =
        firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: firstOrNull()

    private val CapabilityConfig.writable: Boolean
        get() = accessType == AccessType.WRITE || accessType == AccessType.READ_WRITE
    private val CapabilityConfig.readable: Boolean
        get() = accessType == AccessType.READ || accessType == AccessType.READ_WRITE

    private fun clamp(v: Float, b: AreaBounds<Float>?): Float =
        if (b == null) v else v.coerceIn(b.minValue, b.maxValue)

    private fun VehicleGear?.toGear(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }

    private fun warn(what: String, t: Throwable) { Log.w(TAG, "$what: ${t.message}") }

    private companion object {
        const val TAG = "Shimpyo/Vehicle"
        const val CALL_TIMEOUT_MS = 3_000L
        const val SET_WAIT_MS = 600L
        const val RECLINE_MS = 4_000L
        const val DEFAULT_TEMP = 22f
    }
}
