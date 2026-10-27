package app.shimpyo.core

/**
 * 언제 쉬어 가자고 제안할지.
 * 기본 2시간(화물차 휴게 기준과 같음), 졸음이 잦은 이른 오후는 1시간 50분, 새벽은 1시간 20분.
 */
data class RestPolicy(
    val baseThresholdMin: Int = 120,
    val afternoonThresholdMin: Int = 110,
    val dawnThresholdMin: Int = 80,
    val retryAfterDeclineMin: Int = 20,
    val maxSuggestionsPerTrip: Int = 3,
    /** P단으로 이만큼 서 있으면 이미 쉰 것으로 보고 연속 운전 시간을 0으로. */
    val stopResetsAfterMin: Int = 10,
) {
    fun thresholdFor(hour: Int): Int = when (hour) {
        in 2..5 -> dawnThresholdMin
        in 13..15 -> afternoonThresholdMin
        else -> baseThresholdMin
    }

    fun shouldSuggest(
        continuousMin: Int,
        hour: Int,
        suggestionsThisTrip: Int,
        minutesSinceLastDecline: Int?,
    ): Boolean {
        if (suggestionsThisTrip >= maxSuggestionsPerTrip) return false
        if (continuousMin < thresholdFor(hour)) return false
        return minutesSinceLastDecline == null || minutesSinceLastDecline >= retryAfterDeclineMin
    }
}

/**
 * 연속 운전 시간. 기어가 주행 단에 있는 동안 쌓이고, P단으로 [RestPolicy.stopResetsAfterMin] 이상 서 있으면 0이 된다.
 * [minuteMillis]를 줄이면 시연 모드(예: 3초 = 1분)로 같은 흐름을 빨리 보여 준다.
 */
class ContinuousDrivingTracker(private val policy: RestPolicy = RestPolicy()) {
    private var drivingMillis = 0L
    private var parkedMillis = 0L
    private var lastTick: Long? = null

    fun tick(nowMillis: Long, driving: Boolean, minuteMillis: Long) {
        val prev = lastTick
        lastTick = nowMillis
        if (prev == null) return
        val dt = (nowMillis - prev).coerceIn(0, 10_000)
        if (driving) {
            drivingMillis += dt
            parkedMillis = 0
        } else {
            parkedMillis += dt
            if (parkedMillis >= policy.stopResetsAfterMin * minuteMillis) drivingMillis = 0
        }
    }

    fun continuousMinutes(minuteMillis: Long): Int = (drivingMillis / minuteMillis).toInt()

    /** 차의 트립 운전 시간(분)이 더 크면 그 값을 쓴다. */
    fun seedMinutes(minutes: Int, minuteMillis: Long) {
        val m = minutes * minuteMillis
        if (m > drivingMillis) drivingMillis = m
    }

    fun reset() {
        drivingMillis = 0
        parkedMillis = 0
    }
}
