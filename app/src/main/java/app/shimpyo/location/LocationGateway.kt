package app.shimpyo.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Fix(val lat: Double, val lon: Double, val headingDeg: Double?)

interface LocationGateway {
    val fix: StateFlow<Fix?>
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

    override fun stop() {
        fused?.let { runCatching { it.unregisterFusedLocationCallback(fusedListener); it.release() } }
        androidLm?.let { runCatching { it.removeUpdates(androidListener) } }
    }

    private companion object { const val TAG = "Shimpyo/Location" }
}
