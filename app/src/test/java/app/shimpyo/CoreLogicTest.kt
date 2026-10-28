package app.shimpyo

import app.shimpyo.core.ChargeEstimator
import app.shimpyo.core.ChargeRest
import app.shimpyo.core.CommandResult
import app.shimpyo.core.ContinuousDrivingTracker
import app.shimpyo.core.Geo
import app.shimpyo.core.Phase
import app.shimpyo.core.RestAreaHit
import app.shimpyo.core.RestAreaRepository
import app.shimpyo.core.RestPolicy
import app.shimpyo.core.RestSession
import app.shimpyo.core.ShimpyoController
import app.shimpyo.core.SteeringPattern
import app.shimpyo.core.VoiceIntent
import app.shimpyo.core.WakeAnswer
import app.shimpyo.core.WakePlan
import app.shimpyo.core.WakeStep
import app.shimpyo.gleo.GleoReply
import app.shimpyo.vehicle.CabinSnapshot
import app.shimpyo.vehicle.passengersFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreLogicTest {

    private val repo: RestAreaRepository by lazy {
        val f = listOf("src/main/assets/rest_areas.psv", "app/src/main/assets/rest_areas.psv").map(::File).first { it.exists() }
        f.useLines { RestAreaRepository.parse(it) }
    }

    @Test fun `공공데이터 253곳을 모두 읽는다`() {
        assertEquals(253, repo.areas.size)
        assertTrue(repo.areas.all { it.lat in 33.0..39.0 && it.lon in 124.0..132.0 })
    }

    @Test fun `진행 방향 앞쪽의 가장 가까운 쉼터를 고른다`() {
        // 경부선 옥산 졸음쉼터 남쪽 약 8km, 북북동 방향으로 주행
        val f = ShimpyoController.DEMO_FIX
        val hit = repo.nextAhead(f.lat, f.lon, f.headingDeg)
        assertNotNull(hit)
        assertEquals("옥산", hit!!.area.name)
        assertTrue(hit.distanceKm in 7.0..10.0)
        // 반대 방향(남남서)으로 달리면 옥산은 뒤쪽이라 고르지 않는다
        val back = repo.nextAhead(f.lat, f.lon, 205.0)
        assertTrue(back == null || back.area.name != "옥산")
    }

    @Test fun `거리와 방위각`() {
        assertEquals(0.0, Geo.distanceKm(37.5, 127.0, 37.5, 127.0), 1e-9)
        assertEquals(111.2, Geo.distanceKm(37.0, 127.0, 38.0, 127.0), 0.3)
        assertEquals(0.0, Geo.bearingDeg(37.0, 127.0, 38.0, 127.0), 0.5)
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
    }

    @Test fun `시간대별 제안 기준`() {
        val p = RestPolicy()
        assertEquals(80, p.thresholdFor(4))    // 새벽
        assertEquals(110, p.thresholdFor(14))  // 이른 오후
        assertEquals(120, p.thresholdFor(10))
        assertFalse(p.shouldSuggest(100, 10, 0, null))
        assertTrue(p.shouldSuggest(120, 10, 0, null))
        assertTrue(p.shouldSuggest(80, 4, 0, null))
    }

    @Test fun `핸들 패턴이 달라지면 20분 앞당기되 1시간 아래로는 안 내린다`() {
        val p = RestPolicy()
        assertEquals(100, p.thresholdFor(10, patternChanged = true))
        assertEquals(90, p.thresholdFor(14, patternChanged = true))
        assertEquals(60, p.thresholdFor(4, patternChanged = true))   // 새벽 80 → 60
        assertFalse(p.shouldSuggest(100, 10, 0, null))
        assertTrue(p.shouldSuggest(100, 10, 0, null, patternChanged = true))
        assertEquals(50, RestPolicy(dawnThresholdMin = 50).thresholdFor(4, patternChanged = true)) // 원래 1시간 아래면 그대로
    }

    /** 1분 = 1초로 줄여 [minutes]분 동안 [periodMs]마다 핸들을 [amp]도씩 좌우로 흔든다. */
    private fun SteeringPattern.drive(startMs: Long, minutes: Int, periodMs: Long?, amp: Float = 0.5f): Long {
        var now = startMs
        var side = 1
        var nextFlip = now
        val end = now + minutes * 1_000L
        while (now < end) {
            now += 100
            tick(now, true, 1_000L)
            if (periodMs != null && now >= nextFlip) { onAngle(side * amp); side = -side; nextFlip = now + periodMs }
            else onAngle(if (periodMs == null) 0f else -side * amp)
        }
        return now
    }

    @Test fun `핸들 보정이 초반의 절반 아래로 줄면 달라진 것으로 본다`() {
        val sp = SteeringPattern()
        sp.tick(0, true, 1_000L)
        var now = sp.drive(0, 15, periodMs = 500)       // 초반 15분: 분당 반전 약 2번
        assertEquals(2.0, sp.baselinePerMin!!, 0.3)
        assertFalse(sp.changed)
        now = sp.drive(now, 5, periodMs = 500)          // 같은 패턴이면 그대로
        assertFalse(sp.changed)
        now = sp.drive(now, 6, periodMs = null)         // 보정이 사라짐
        assertTrue(sp.changed)
        // P단으로 10분 서 있으면(쉼) 처음부터 다시 배운다
        repeat(11) { now += 1_000; sp.tick(now, false, 1_000L) }
        assertFalse(sp.changed)
        assertNull(sp.baselinePerMin)
    }

    @Test fun `반전은 정한 각도 이상 되돌아올 때만 센다`() {
        val sp = SteeringPattern(gapDeg = 1f)
        sp.tick(0, true, 1_000L)
        var now = 0L
        // 0.4도로 흔들면 반전이 아니다 → 초반 기준이 0이라 비교하지 않는다
        now = sp.drive(now, 15, periodMs = 500, amp = 0.2f)
        assertEquals(0.0, sp.baselinePerMin!!, 1e-9)
        sp.drive(now, 6, periodMs = null)
        assertFalse(sp.changed)
    }

    @Test fun `충전 목표까지 남은 시간과 쉼표 길이`() {
        val e = ChargeEstimator()
        val m = 60_000L
        e.update(0, 40, true)
        assertNull(e.minutesTo(80, m))                 // 아직 오르는 걸 못 봄
        e.update(2 * m, 42, true)                      // 분당 1%
        assertEquals(38, e.minutesTo(80, m))
        e.update(3 * m, 42, true)
        assertEquals(38, e.minutesTo(80, m))           // 같은 값이면 마지막 변화 기준
        e.update(4 * m, 81, true)
        assertEquals(0, e.minutesTo(80, m))
        e.update(5 * m, 81, false)                     // 충전이 끝나면 지운다
        assertNull(e.minutesTo(80, m))
        assertEquals(30, ChargeRest.restMinutes(38, 20))
        assertEquals(10, ChargeRest.restMinutes(4, 20))
        assertEquals(18, ChargeRest.restMinutes(18, 20))
        assertEquals(20, ChargeRest.restMinutes(null, 20))
    }

    @Test fun `거절하면 20분 뒤에 한 번 더, 한 주행에 최대 3번`() {
        val p = RestPolicy()
        assertFalse(p.shouldSuggest(130, 10, 1, 5))
        assertTrue(p.shouldSuggest(130, 10, 1, 20))
        assertFalse(p.shouldSuggest(200, 10, 3, null))
        // 쉬고 나서 연속 운전 시간이 줄었으면 거절 뒤라도 제안하지 않는다
        assertFalse(p.shouldSuggest(30, 10, 1, 40))
    }

    @Test fun `연속 운전은 주행 중에만 쌓이고 10분 정차하면 0`() {
        val t = ContinuousDrivingTracker()
        val m = 1_000L // 테스트용 1분 = 1초
        var now = 0L
        t.tick(now, true, m)
        repeat(90) { now += 1_000; t.tick(now, true, m) }
        assertEquals(90, t.continuousMinutes(m))
        repeat(5) { now += 1_000; t.tick(now, false, m) }
        assertEquals(90, t.continuousMinutes(m))
        repeat(6) { now += 1_000; t.tick(now, false, m) }
        assertEquals(0, t.continuousMinutes(m))
    }

    @Test fun `깨우기는 빛, 바람, 자세, 소리 순서`() {
        val m = 60_000L
        assertEquals(emptyList<WakeStep>(), WakePlan.dueSteps(5 * m, emptySet(), m))
        assertEquals(listOf(WakeStep.LIGHT), WakePlan.dueSteps(2 * m, emptySet(), m))
        assertEquals(listOf(WakeStep.AIR), WakePlan.dueSteps(55_000, setOf(WakeStep.LIGHT), m))
        assertEquals(listOf(WakeStep.LIGHT, WakeStep.AIR, WakeStep.SEAT, WakeStep.ALARM), WakePlan.dueSteps(0, emptySet(), m))
    }

    @Test fun `짧은 대답 알아듣기`() {
        assertEquals(true, VoiceIntent.parse("응"))
        assertEquals(true, VoiceIntent.parse("네 쉬어 갈게요"))
        assertEquals(true, VoiceIntent.parse("그래"))
        assertEquals(false, VoiceIntent.parse("아니 괜찮아"))
        assertEquals(false, VoiceIntent.parse("나중에"))
        assertNull(VoiceIntent.parse("어디야"))
        assertNull(VoiceIntent.parse(""))
    }

    @Test fun `받침에 맞는 조사`() {
        assertEquals("옥산 졸음쉼터를", app.shimpyo.core.Josa.eulReul("옥산 졸음쉼터"))
        assertEquals("옥산을", app.shimpyo.core.Josa.eulReul("옥산"))
        assertEquals("옥산으로", app.shimpyo.core.Josa.ro("옥산"))
        assertEquals("진주로", app.shimpyo.core.Josa.ro("진주"))
        assertEquals("서울로", app.shimpyo.core.Josa.ro("서울"))       // ㄹ 받침
        assertEquals("대관령으로", app.shimpyo.core.Josa.ro("대관령"))
    }

    @Test fun `시간 표시`() {
        assertEquals("40분", ShimpyoController.durationText(40))
        assertEquals("2시간", ShimpyoController.durationText(120))
        assertEquals("1시간 50분", ShimpyoController.durationText(110))
    }

    private fun session(phase: Phase = Phase.RESTING, endAt: Long = 1_200_000L, paused: Long? = null, savedAt: Long = 100_000L) =
        RestSession(
            phase = phase, startedAt = 0L, endAt = endAt, pausedRemainingMs = paused, minutes = 20,
            seatReclined = true, passengers = false, placeName = "옥산 졸음쉼터",
            applied = listOf("운전석 등받이 젖힘", "외기 유입 · 1℃ 낮춤"), savedAt = savedAt,
            snapshot = CabinSnapshot(backrest = 100, climatePower = true, temperature = 22.5f, fanSpeed = 3,
                recirculation = true, window = 0, cabinLightSwitch = "AUTOMATIC", ambientBrightness = 80, ventilation = null),
        )

    @Test fun `쉼표 상태를 저장했다가 그대로 되살린다`() {
        val s = session(paused = 300_000L)
        assertEquals(s, RestSession.decode(s.encode()))
        val charging = s.copy(chargeTarget = 80)
        assertEquals(charging, RestSession.decode(charging.encode()))
        assertNull(RestSession.decode("phase=NOPE"))
    }

    @Test fun `앱이 다시 켜지면 30분 안이면 이어 가고, 오래 지났으면 되돌리기만`() {
        val s = session()
        assertEquals(RestSession.Resume.CONTINUE, s.resumeAction(now = 600_000L))                 // 쉼표 도중
        assertEquals(RestSession.Resume.CONTINUE, s.resumeAction(now = 1_200_000L + 29 * 60_000L)) // 끝나고 29분
        assertEquals(RestSession.Resume.RESTORE, s.resumeAction(now = 1_200_000L + 31 * 60_000L))
        // 문 열림으로 멈춘 쉼표·출발 준비는 마지막 저장 시각 기준
        assertEquals(RestSession.Resume.CONTINUE, session(paused = 60_000L).resumeAction(now = 100_000L + 10 * 60_000L))
        assertEquals(RestSession.Resume.RESTORE, session(Phase.DEPARTING).resumeAction(now = 100_000L + 40 * 60_000L))
    }

    @Test fun `깨운 뒤 대답 알아듣기`() {
        assertEquals(WakeAnswer.AWAKE, VoiceIntent.parseWake("네"))
        assertEquals(WakeAnswer.AWAKE, VoiceIntent.parseWake("응 깼어"))
        assertEquals(WakeAnswer.AWAKE, VoiceIntent.parseWake("일어났어요"))
        assertEquals(WakeAnswer.MORE, VoiceIntent.parseWake("아직 안 깼어"))
        assertEquals(WakeAnswer.MORE, VoiceIntent.parseWake("5분만 더"))
        assertEquals(WakeAnswer.MORE, VoiceIntent.parseWake("조금 더"))
        assertEquals(WakeAnswer.MORE, VoiceIntent.parseWake("너무 졸려"))
        assertNull(VoiceIntent.parseWake("음"))
    }

    @Test fun `동승자 판단 — 운전석 값을 착석 기준으로`() {
        val f: (Boolean?, List<Boolean?>) -> Boolean? = { d, o -> passengersFrom(d, o) }
        // SDK가 착석을 true로 주는 경우
        assertEquals(true, f(true, listOf(false, false, false, true)))
        assertEquals(false, f(true, listOf(false, false, false, false)))
        // 에뮬레이터처럼 뜻이 반대로 나오는 경우(빈 좌석 true, 앉은 좌석 false)
        assertEquals(false, f(false, listOf(true, true, true, true)))
        assertEquals(true, f(false, listOf(true, true, true, false)))
        // 모르면 null
        assertNull(f(null, listOf(true, false)))
        assertNull(f(true, listOf(null, null)))
    }

    @Test fun `Gleo 응답은 규격대로 영어 문장과 상태`() {
        val hit = RestAreaHit(repo.areas.first { it.name == "옥산" }, 8.04)
        val routed = GleoReply.of(CommandResult.Routed(hit))
        assertEquals("success", routed.status)
        assertEquals("Added 옥산 졸음쉼터, 8.0 km ahead, as a waypoint.", routed.message)
        assertEquals(8.0, routed.data?.get("distanceKm"))
        assertEquals("fail", GleoReply.of(CommandResult.NoArea).status)
        assertEquals("fail", GleoReply.of(CommandResult.NotResting).status)
        assertEquals("success", GleoReply.of(CommandResult.RestStarted(15)).status)
    }
}
