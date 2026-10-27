package app.shimpyo.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class Fix(val lat: Double, val lon: Double, val headingDeg: Double?)

interface LocationGateway {
    val fix: StateFlow<Fix?>
    /** [watchArrival]로 지정한 곳에 들어섰을 때. */
    val arrivals: SharedFlow<Unit>
    /** 플랫폼 근접 알림을 건다. 지원하지 않으면 false — 그때는 위치로 직접 판정한다. */
    fun watchArrival(lat: Double, lon: Double, radiusMeters: Float): Boolean
    fun clearArrival()
    fun start()
    fun stop()
}

/**
 * Pleos FusedLocation으로 차량 위치를 받는다.
 * 지원하지 않는 환경이면 Android 위치(GPS)로 대신한다.
 */
class VehicleLocationGateway(context: Context) : LocationGateway {
    private val app = context.applicationContext
    private val _fix = MutableStateFlow<Fix?>(null)
    override val fix: StateFlow<Fix?> = _fix
    private var fused: FusedLocation? = null
    private var androidLm: LocationManager? = null
    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val arrivals: SharedFlow<Unit> = _arrivals
    @Volatile private var alertId: String? = null
    /** 지운 알림의 늦은 콜백을 무시하려고 등록마다 올린다. */
    @Volatile private var alertToken = 0

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            _fix.value = Fix(location.latitude, location.longitude, location.heading.toDouble().takeIf { it > 0 })
        }
        override fun onError(exception: Exception) { Log.w(TAG, "fused: ${exception.message}") }
    }

    private val androidListener = LocationListener { loc: Location ->
        _fix.value = Fix(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
    }

    override fun start() {
        val f = runCatching { FusedLocation(app) }.getOrNull()
        if (f != null && runCatching { f.isSupported() }.getOrDefault(false)) {
            runCatching {
                f.initialize()
                f.getLastKnownLocation(fusedListener)
                f.registerFusedLocationCallback(fusedListener)
                fused = f
            }.onFailure { Log.w(TAG, "fused start: ${it.message}") }
        }
        if (fused == null) startAndroid()
    }

    @SuppressLint("MissingPermission")
    private fun startAndroid() {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { androidListener.onLocationChanged(it) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 10f, androidListener, Looper.getMainLooper())
            androidLm = lm
        }.onFailure { Log.w(TAG, "android location: ${it.message}") }
    }

    override fun watchArrival(lat: Double, lon: Double, radiusMeters: Float): Boolean {
        val f = fused ?: return false
        clearArrival()
        val token = ++alertToken
        val listener = object : ProximityAlertListener {
            override fun onRegistered(alertId: String) {
                Log.i(TAG, "proximity registered $alertId")
                if (token == alertToken) this@VehicleLocationGateway.alertId = alertId
                else runCatching { f.removeProximityAlert(alertId) }
            }
            override fun onTransition(event: ProximityAlertEvent) {
                Log.i(TAG, "proximity ${event.transition} ${event.alertId}")
                if (token == alertToken && event.transition == ProximityTransition.ENTER) _arrivals.tryEmit(Unit)
            }
            override fun onError(exception: Exception) { Log.w(TAG, "proximity: ${exception.message}") }
        }
        return runCatching {
            f.addProximityAlert(ProximityAlertRequest(lat, lon, radiusMeters, setOf(ProximityTransition.ENTER)), listener)
            true
        }.onFailure { Log.w(TAG, "proximity add: ${it.message}") }.getOrDefault(false)
    }

    override fun clearArrival() {
        alertToken++
        val id = alertId ?: return
        alertId = null
        fused?.let { f -> runCatching { f.removeProximityAlert(id) } }
    }

    override fun stop() {
        clearArrival()
        fused?.let { runCatching { it.unregisterFusedLocationCallback(fusedListener); it.release() } }
        androidLm?.let { runCatching { it.removeUpdates(androidListener) } }
    }

    private companion object { const val TAG = "Shimpyo/Location" }
}
