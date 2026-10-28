package app.shimpyo.core

import kotlin.math.abs

/**
 * 핸들 미세 보정(조향 반전) 횟수가 운전 초반과 달라졌는지 본다.
 * 졸음 판정이 아니다 — 졸릴 때 반전이 늘었다·줄었다는 연구가 섞여 있어, 제안을 조금 앞당기는 보조 신호로만 쓴다.
 * 각도는 메모리에서만 세고 저장하지 않는다.
 *
 * 반전: 앞바퀴 각도가 한쪽으로 가다가 [gapDeg] 이상 반대로 돌아오면 1번.
 * 주행 기어인 동안의 시간만 세고, P단으로 [resetAfterParkedMin] 이상 서 있으면(이미 쉼) 처음부터 다시 배운다.
 */
class SteeringPattern(
    private val gapDeg: Float = 0.1f,
    private val baselineMin: Int = 15,
    private val windowMin: Int = 5,
    /** 초반 반전이 분당 이보다 적으면(직선만 달림 등) 비교하지 않는다. */
    private val minBaselinePerMin: Double = 1.0,
    /** 초반보다 이 배 이상 늘거나 줄면 "달라짐". */
    private val changeRatio: Double = 2.0,
    private val resetAfterParkedMin: Int = 10,
) {
    private var drivingMs = 0L
    private var parkedMs = 0L
    private var lastTick: Long? = null
    private var driving = false

    private var extreme: Float? = null
    private var dir = 0
    /** 반전이 일어난 주행 시각(ms). */
    private val reversals = ArrayDeque<Long>()
    private var baselineCount: Int? = null

    /** 한 번 달라지면 쉬거나 처음부터 다시 배울 때까지 유지한다(기준이 들쭉날쭉하지 않게). */
    var changed = false
        private set
    var baselinePerMin: Double? = null
        private set
    var recentPerMin: Double? = null
        private set

    fun tick(nowMs: Long, driving: Boolean, minuteMillis: Long) {
        val prev = lastTick
        lastTick = nowMs
        this.driving = driving
        if (prev == null) return
        val dt = (nowMs - prev).coerceIn(0, 10_000)
        if (driving) {
            drivingMs += dt
            parkedMs = 0
        } else {
            parkedMs += dt
            if (parkedMs >= resetAfterParkedMin * minuteMillis) reset()
            return
        }
        evaluate(minuteMillis)
    }

    fun onAngle(deg: Float) {
        if (!driving) return
        val e = extreme ?: run { extreme = deg; return }
        when (dir) {
            0 -> if (abs(deg - e) >= gapDeg) { dir = if (deg > e) 1 else -1; extreme = deg }
            1 -> if (deg > e) extreme = deg else if (e - deg >= gapDeg) { reversals.addLast(drivingMs); dir = -1; extreme = deg }
            else -> if (deg < e) extreme = deg else if (deg - e >= gapDeg) { reversals.addLast(drivingMs); dir = 1; extreme = deg }
        }
    }

    private fun evaluate(minuteMillis: Long) {
        val baseEnd = baselineMin * minuteMillis
        if (baselineCount == null && drivingMs >= baseEnd) {
            baselineCount = reversals.count { it < baseEnd }
            baselinePerMin = baselineCount!!.toDouble() / baselineMin
        }
        val base = baselinePerMin ?: return
        if (drivingMs < baseEnd + windowMin * minuteMillis) return
        val from = drivingMs - windowMin * minuteMillis
        // 초반 횟수는 이미 셌으므로 최근 구간 앞은 버린다
        while (reversals.isNotEmpty() && reversals.first() < from) reversals.removeFirst()
        val recent = reversals.count { it >= from }.toDouble() / windowMin
        recentPerMin = recent
        if (!changed && base >= minBaselinePerMin && (recent >= base * changeRatio || recent <= base / changeRatio)) changed = true
    }

    fun reset() {
        drivingMs = 0; parkedMs = 0
        extreme = null; dir = 0
        reversals.clear()
        baselineCount = null; baselinePerMin = null; recentPerMin = null
        changed = false
    }
}
