package app.shimpyo.core

import kotlin.math.ceil

/** 충전하며 쉬는 쉼표: 몇 분 쉴지, 언제 깨울지. */
object ChargeRest {
    /** 급속 충전이 느려지기 시작하는 지점. 차의 충전 한도 값은 Vehicle SDK로 읽을 수 없어 앱 기준으로 둔다. */
    const val TARGET_PCT = 80

    /** 목표까지 남은 시간에 맞춘 쉼표 길이. 30분을 넘기면 깊은 잠에 들어 깰 때 멍해서 10~30분 안으로. */
    fun restMinutes(minutesToTarget: Int?, default: Int): Int = minutesToTarget?.coerceIn(10, 30) ?: default
}

/**
 * 충전 중 배터리 %가 오르는 빠르기로 목표까지 남은 시간을 어림한다.
 * 충전기·배터리 온도마다 속도가 달라 처음 1%가 오를 때까지는 모른다(null).
 */
class ChargeEstimator {
    private var first: Pair<Long, Int>? = null
    private var last: Pair<Long, Int>? = null

    fun update(nowMs: Long, soc: Int?, charging: Boolean) {
        if (!charging || soc == null) { first = null; last = null; return }
        if (first == null) first = nowMs to soc
        if (last?.second != soc) last = nowMs to soc
    }

    /** 목표까지 남은 분. 오르는 걸 아직 못 봤으면 null. */
    fun minutesTo(target: Int, minuteMillis: Long): Int? {
        val (t0, s0) = first ?: return null
        val (t1, s1) = last ?: return null
        if (s1 >= target) return 0
        if (s1 <= s0 || t1 <= t0) return null
        val perMin = (s1 - s0) / ((t1 - t0).toDouble() / minuteMillis)
        return ceil((target - s1) / perMin).toInt()
    }
}
