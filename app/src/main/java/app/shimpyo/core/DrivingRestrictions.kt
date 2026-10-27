package app.shimpyo.core

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AAOS CarUxRestrictions를 구독한다. 주행 규제 중에는 설정·기록 화면을 막고 큰 버튼과 음성만 남긴다.
 * 차량이 아닌 기기에서는 항상 false.
 */
class DrivingRestrictions(private val context: Context) {
    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r -> apply(r) }

    fun start() {
        try {
            val c = Car.createCar(context.applicationContext) ?: return
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            m.registerListener(listener)
            m.currentCarUxRestrictions?.let { apply(it) }
            car = c
            manager = m
        } catch (t: Throwable) {
            Log.i(TAG, "UX restrictions unavailable: ${t.message}")
        }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
    }

    private fun apply(r: CarUxRestrictions) {
        _restricted.value = r.isRequiresDistractionOptimization
    }

    private companion object { const val TAG = "Shimpyo/UxR" }
}
