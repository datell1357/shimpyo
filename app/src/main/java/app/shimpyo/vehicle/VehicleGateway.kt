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
    val evBattery: Boolean = false,
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

interface VehicleGateway {
    val connected: StateFlow<Boolean>
    val gear: StateFlow<Gear>
    val tripDrivingMinutes: StateFlow<Int?>
    val driverSeatbeltBuckled: StateFlow<Boolean?>
    val batteryPercent: StateFlow<Int?>
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
    suspend fun restoreDriverSeat(before: CabinSnapshot): Boolean

    /** 창문·공조·조명·통풍을 쉼표 전으로. */
    suspend fun restoreCabin(before: CabinSnapshot)

    fun start()
    fun stop()
}
