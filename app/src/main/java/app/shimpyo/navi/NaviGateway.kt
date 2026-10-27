package app.shimpyo.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import app.shimpyo.core.RestArea
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface NaviGateway {
    val available: StateFlow<Boolean>
    val hasRoute: StateFlow<Boolean>
    /** 목적지까지 남은 (초, 미터). */
    val remainingToDestination: StateFlow<Pair<Int, Int>?>
    /** 쉼터 경유지(또는 쉼터 목적지)에 도착. */
    val arrivedAtRestStop: SharedFlow<Unit>

    /** 길 안내 중이면 경유지로, 아니면 목적지로 쉼터를 넣는다. */
    fun goVia(area: RestArea): Boolean
    /** 쉼터를 경로에서 빼고 원래 목적지 안내를 잇는다. */
    fun clearRestStop()
    fun start()
    fun stop()
}

class PleosNaviGateway(context: Context) : NaviGateway {
    private val navi = NaviHelper(context.applicationContext)
    private val _available = MutableStateFlow(false)
    private val _hasRoute = MutableStateFlow(false)
    private val _remaining = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _arrived = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val available: StateFlow<Boolean> = _available
    override val hasRoute: StateFlow<Boolean> = _hasRoute
    override val remainingToDestination: StateFlow<Pair<Int, Int>?> = _remaining
    override val arrivedAtRestStop: SharedFlow<Unit> = _arrived

    private enum class StopMode { NONE, WAYPOINT, DESTINATION }
    private var stopMode = StopMode.NONE

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { _hasRoute.value = true }
        override fun onRouteCancelled() { _hasRoute.value = false; _remaining.value = null }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            _hasRoute.value = true
            _remaining.value = drivingInfo.destination.duration to drivingInfo.destination.distance
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            if (stopMode == StopMode.WAYPOINT) _arrived.tryEmit(Unit)
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            if (stopMode == StopMode.DESTINATION) _arrived.tryEmit(Unit)
            _hasRoute.value = false
        }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "navi error $errorCode") }
    }

    override fun start() {
        runCatching {
            navi.initialize(
                onConnected = {
                    _available.value = true
                    navi.addListener(listener)
                    runCatching { navi.getRouteStateInfo() }
                },
                onFailed = { t -> Log.w(TAG, "navi connect failed: ${t.message}") },
            )
        }.onFailure { Log.w(TAG, "navi init failed: ${it.message}") }
    }

    override fun stop() {
        runCatching { navi.removeListener(listener); navi.release() }
    }

    override fun goVia(area: RestArea): Boolean {
        if (!_available.value) return false
        return runCatching {
            if (_hasRoute.value) {
                navi.addWaypoint(
                    RequestWaypointInfo(
                        latitude = area.lat, longitude = area.lon, poiName = area.displayName,
                        waypointIndex = WaypointIndex.FIRST, poiId = "", address = area.routeName, poiSubId = "0",
                    )
                )
                stopMode = StopMode.WAYPOINT
            } else {
                navi.requestRoute(
                    RouteInfo(
                        latitude = area.lat, longitude = area.lon, poiName = area.displayName,
                        poiId = "", address = area.routeName, poiSubId = "0",
                    )
                )
                stopMode = StopMode.DESTINATION
            }
            true
        }.onFailure { Log.w(TAG, "goVia failed: ${it.message}") }.getOrDefault(false)
    }

    override fun clearRestStop() {
        runCatching {
            when (stopMode) {
                StopMode.WAYPOINT -> navi.removeWaypoint(WaypointIndex.FIRST)
                StopMode.DESTINATION -> navi.cancelRoute()
                StopMode.NONE -> Unit
            }
        }
        stopMode = StopMode.NONE
    }

    private companion object { const val TAG = "Shimpyo/Navi" }
}

/** 내비가 없는 차·기기용. 쉼터 도착은 위치로 판정한다. */
class NoNaviGateway : NaviGateway {
    override val available: StateFlow<Boolean> = MutableStateFlow(false)
    override val hasRoute: StateFlow<Boolean> = MutableStateFlow(false)
    override val remainingToDestination: StateFlow<Pair<Int, Int>?> = MutableStateFlow(null)
    override val arrivedAtRestStop: SharedFlow<Unit> = MutableSharedFlow()
    override fun goVia(area: RestArea) = false
    override fun clearRestStop() = Unit
    override fun start() = Unit
    override fun stop() = Unit
}
