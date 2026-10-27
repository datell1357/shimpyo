package app.shimpyo.vehicle

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pleos Vehicle Service가 없는 기기(일반 폰·태블릿 에뮬레이터)에서 화면 흐름을 확인하기 위한 가상 차량.
 * 기어·안전벨트는 앱의 시연 패널에서 바꾼다.
 */
class SimVehicleGateway : VehicleGateway {
    private val _gear = MutableStateFlow(Gear.DRIVE)
    private val _belt = MutableStateFlow<Boolean?>(true)
    private val _battery = MutableStateFlow<Int?>(72)
    private val _door = MutableStateFlow<Boolean?>(false)

    override val connected: StateFlow<Boolean> = MutableStateFlow(true)
    override val ready: StateFlow<Boolean> = MutableStateFlow(true)
    override val gear: StateFlow<Gear> = _gear
    override val doorOpen: StateFlow<Boolean?> = _door
    override val tripDrivingMinutes: StateFlow<Int?> = MutableStateFlow(null)
    override val driverSeatbeltBuckled: StateFlow<Boolean?> = _belt
    override val batteryPercent: StateFlow<Int?> = _battery
    override val features: StateFlow<CabinFeatures> = MutableStateFlow(
        CabinFeatures(
            seatRecline = true, seatVentilation = true, seatbelt = true, seatOccupancy = false,
            climate = true, window = true, cabinLight = true, ambientLight = true, doorLock = true,
            doorPosition = true, evBattery = true, tripTime = false,
        )
    )

    fun setGear(g: Gear) { _gear.value = g }
    fun setSeatbelt(on: Boolean) { _belt.value = on }
    fun setDoorOpen(open: Boolean) { _door.value = open }

    override suspend fun passengersPresent(): Boolean? = null
    override suspend fun snapshot() = CabinSnapshot(backrest = 0, temperature = 22f, fanSpeed = 3, window = 0)
    override suspend fun lockDoors() = act()
    override suspend fun reclineDriverSeat() = act(800)
    override suspend fun setRestClimate(before: CabinSnapshot) = act()
    override suspend fun openWindowSlightly() = act()
    override suspend fun dimCabin() = act()
    override suspend fun brightenAmbient() = act()
    override suspend fun coolBreeze(before: CabinSnapshot) = act()
    override suspend fun restoreDriverSeat(before: CabinSnapshot) = act(800)
    override suspend fun restoreCabin(before: CabinSnapshot) { act() }
    override fun start() = Unit
    override fun stop() = Unit

    private suspend fun act(ms: Long = 250): Boolean { delay(ms); return true }
}
