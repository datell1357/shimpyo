package app.shimpyo

import app.shimpyo.core.ContinuousDrivingTracker
import app.shimpyo.core.Geo
import app.shimpyo.core.RestAreaRepository
import app.shimpyo.core.RestPolicy
import app.shimpyo.core.ShimpyoController
import app.shimpyo.core.VoiceIntent
import app.shimpyo.core.WakePlan
import app.shimpyo.core.WakeStep
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
}
