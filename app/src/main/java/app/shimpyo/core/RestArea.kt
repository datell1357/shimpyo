package app.shimpyo.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 한국도로공사 「전국졸음쉼터표준데이터」 한 줄. */
data class RestArea(
    val name: String,
    val roadKind: String,
    val routeName: String,
    val routeNo: String,
    val direction: String,
    val lat: Double,
    val lon: Double,
    val hasToilet: Boolean,
    val parkingSpaces: Int,
) {
    val displayName: String get() = "$name 졸음쉼터"
}

data class RestAreaHit(val area: RestArea, val distanceKm: Double)

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0088

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    /** 북쪽 기준 시계 방향 0~360도. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs((a - b) % 360)
        return if (d > 180) 360 - d else d
    }
}

class RestAreaRepository(val areas: List<RestArea>) {

    /**
     * 진행 방향 앞쪽에서 가장 가까운 졸음쉼터.
     * 방향(heading)을 모르면 거리만 본다.
     */
    fun nextAhead(
        lat: Double,
        lon: Double,
        headingDeg: Double?,
        maxKm: Double = 40.0,
        minKm: Double = 0.5,
        maxAngle: Double = 50.0,
    ): RestAreaHit? = areas.asSequence()
        .map { RestAreaHit(it, Geo.distanceKm(lat, lon, it.lat, it.lon)) }
        .filter { it.distanceKm in minKm..maxKm }
        .filter { hit ->
            headingDeg == null ||
                Geo.angleDiff(Geo.bearingDeg(lat, lon, hit.area.lat, hit.area.lon), headingDeg) <= maxAngle
        }
        .minByOrNull { it.distanceKm }

    /** 차가 이미 쉼터 안에 있는지(도착 판정). */
    fun within(lat: Double, lon: Double, radiusKm: Double = 0.35): RestAreaHit? = areas.asSequence()
        .map { RestAreaHit(it, Geo.distanceKm(lat, lon, it.lat, it.lon)) }
        .filter { it.distanceKm <= radiusKm }
        .minByOrNull { it.distanceKm }

    companion object {
        /** `이름|도로종류|노선명|노선번호|노선방향|위도|경도|화장실|주차면수`, `#`로 시작하는 줄은 주석. */
        fun parse(lines: Sequence<String>): RestAreaRepository = RestAreaRepository(
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val c = line.split('|')
                    if (c.size < 9) return@mapNotNull null
                    val lat = c[5].toDoubleOrNull() ?: return@mapNotNull null
                    val lon = c[6].toDoubleOrNull() ?: return@mapNotNull null
                    RestArea(
                        name = c[0], roadKind = c[1], routeName = c[2], routeNo = c[3],
                        direction = c[4], lat = lat, lon = lon,
                        hasToilet = c[7] == "Y", parkingSpaces = c[8].toIntOrNull() ?: 0,
                    )
                }
                .toList()
        )
    }
}
