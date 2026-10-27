package app.shimpyo

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import app.shimpyo.core.DrivingRestrictions
import app.shimpyo.core.RestAreaRepository
import app.shimpyo.core.RestLog
import app.shimpyo.core.ShimpyoController
import app.shimpyo.location.VehicleLocationGateway
import app.shimpyo.navi.NoNaviGateway
import app.shimpyo.navi.PleosNaviGateway
import app.shimpyo.vehicle.PleosVehicleGateway
import app.shimpyo.vehicle.SimVehicleGateway
import app.shimpyo.voice.PleosVoiceGateway
import kotlinx.coroutines.MainScope

class ShimpyoApp : Application() {
    lateinit var controller: ShimpyoController
        private set
    lateinit var vehicle: app.shimpyo.vehicle.VehicleGateway
        private set
    private var started = false

    override fun onCreate() {
        super.onCreate()
        // 차량(AAOS)이면 Pleos SDK, 아니면 화면 확인용 가상 차량
        val isCar = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val areas = assets.open("rest_areas.psv").bufferedReader().useLines { RestAreaRepository.parse(it) }
        vehicle = if (isCar) PleosVehicleGateway(this) else SimVehicleGateway()
        controller = ShimpyoController(
            scope = MainScope(),
            vehicle = vehicle,
            navi = if (isCar) PleosNaviGateway(this) else NoNaviGateway(),
            location = VehicleLocationGateway(this),
            voice = PleosVoiceGateway(this),
            areas = areas,
            log = RestLog(this),
            restrictions = if (isCar) DrivingRestrictions(this) else null,
            prefs = getSharedPreferences("settings", Context.MODE_PRIVATE),
        )
    }

    /** 위치 권한을 받은 뒤 화면에서 부른다. */
    fun startOnce() {
        if (started) return
        started = true
        controller.start()
        TripService.start(this)
    }

    /** 화면 없이 깨어났을 때(서비스 재시작, Gleo 명령): 권한이 이미 있을 때만 시작한다. */
    fun startIfPermitted() {
        val granted = LOCATION_PERMISSIONS.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted) startOnce()
    }

    companion object {
        val LOCATION_PERMISSIONS = listOf(
            "pleos.car.permission.FUSED_LOCATION", // 보호 수준 dangerous
            android.Manifest.permission.ACCESS_FINE_LOCATION, // FusedLocation 미지원 환경의 대체 경로
        )
    }
}
