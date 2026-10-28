package app.shimpyo.vehicle

import kotlinx.coroutines.flow.StateFlow

enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN;
    val isDriving: Boolean get() = this == DRIVE || this == REVERSE
}

/** 이 차에서 쉼표가 직접 움직일 수 있는 기능(차종마다 다름, capability 확인 결과). */
data class CabinFeatures(
    val seatRecline: Boolean = false,
    val seatVentilation: Boolean = false,
    val seatbelt: Boolean = false,
    val seatOccupancy: Boolean = false,
    val climate: Boolean = false,
    val window: Boolean = false,
    val cabinLight: Boolean = false,
    val ambientLight: Boolean = false,
    val doorLock: Boolean = false,
    val doorPosition: Boolean = false,
    val evBattery: Boolean = false,
    val evCharge: Boolean = false,
    val steering: Boolean = false,
    val tripTime: Boolean = false,
) {
    val any: Boolean get() = seatRecline || climate || window || cabinLight || ambientLight
}

/** 쉼표 시작 전 상태 — 끝나면 이대로 되돌린다. */
data class CabinSnapshot(
    val backrest: Int? = null,
    val climatePower: Boolean? = null,
    val temperature: Float? = null,
    val fanSpeed: Int? = null,
    val recirculation: Boolean? = null,
    val window: Int? = null,
    /** LightSwitch 이름(OFF/ON/AUTOMATIC…) */
    val cabinLightSwitch: String? = null,
    val ambientBrightness: Int? = null,
    val ventilation: Int? = null,
)

/**
 * 좌석 착석 값으로 동승자 여부를 판단한다. 알 수 없으면 null.
 * 쉼표 중 운전석에는 반드시 사람이 있으므로 운전석 값을 "착석"의 기준으로 삼는다 —
 * Vehicle SDK 2.3.2는 원시값 1을 착석(true)으로 돌려주는데 AAOS에서 1은 빈 좌석이라,
 * Pleos Emulator v2.1에서는 빈 좌석이 true, 앉은 좌석이 false로 읽힌다.
 */
fun passengersFrom(driver: Boolean?, others: List<Boolean?>): Boolean? {
    if (driver == null) return null
    val known = others.filterNotNull()
    if (known.isEmpty()) return null
    return known.any { it == driver }
}

interface VehicleGateway {
    val connected: StateFlow<Boolean>
    /** 기능 확인(capability)까지 끝나 시트·공조를 움직일 수 있는 상태. */
    val ready: StateFlow<Boolean>
    val gear: StateFlow<Gear>
    /** 문이 하나라도 열렸는지. 알 수 없으면 null. */
    val doorOpen: StateFlow<Boolean?>
    val tripDrivingMinutes: StateFlow<Int?>
    val driverSeatbeltBuckled: StateFlow<Boolean?>
    val batteryPercent: StateFlow<Int?>
    /** EV 충전 중인지(충전 스위치). 충전은 P단에서만 되므로 P단에서만 확인한다. 알 수 없으면 null. */
    val charging: StateFlow<Boolean?>
    /** 앞바퀴 조향각(도, 왼쪽이 음수). 알 수 없으면 null. */
    val steeringDeg: StateFlow<Float?>
    val features: StateFlow<CabinFeatures>

    /** 탑승객이 있는 좌석(운전석 제외). 알 수 없으면 null. */
    suspend fun passengersPresent(): Boolean?

    suspend fun snapshot(): CabinSnapshot

    // 쉼표 모드
    suspend fun lockDoors(): Boolean
    suspend fun reclineDriverSeat(): Boolean
    suspend fun setRestClimate(before: CabinSnapshot): Boolean
    suspend fun openWindowSlightly(): Boolean
    suspend fun dimCabin(): Boolean

    // 깨우기
    suspend fun brightenAmbient(): Boolean
    suspend fun coolBreeze(before: CabinSnapshot): Boolean
    /** 등받이를 쉼표 전으로. 차량이 거부하면(주행 기어 등) false. */
    suspend fun restoreDriverSeat(before: CabinSnapshot): Boolean

    /** 창문·공조·조명·통풍을 쉼표 전으로. */
    suspend fun restoreCabin(before: CabinSnapshot)

    fun start()
    fun stop()
}
