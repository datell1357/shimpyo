package app.shimpyo.core

import android.content.SharedPreferences
import app.shimpyo.vehicle.CabinSnapshot
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * 쉼표 중인 상태. 앱이 꺼졌다 다시 켜져도 이어 가거나 쉼표 전으로 되돌릴 수 있게 저장한다.
 * 시각은 모두 System.currentTimeMillis 기준.
 */
data class RestSession(
    val phase: Phase,
    val startedAt: Long,
    val endAt: Long,
    /** 문이 열려 멈춘 동안의 남은 시간. 멈추지 않았으면 null. */
    val pausedRemainingMs: Long?,
    val minutes: Int,
    /** 등받이를 젖힌 채인지 — 되돌릴 때 이미 세운 시트를 한 번 더 움직이지 않게. */
    val seatReclined: Boolean,
    val passengers: Boolean,
    val placeName: String?,
    val applied: List<String>,
    val savedAt: Long,
    val snapshot: CabinSnapshot,
) {
    enum class Resume { CONTINUE, RESTORE }

    /** 다시 켜진 시점에 이어 갈지, 오래 지나 되돌리기만 할지. */
    fun resumeAction(now: Long): Resume {
        val overdue = if (phase == Phase.RESTING && pausedRemainingMs == null) now - endAt else now - savedAt
        return if (overdue > STALE_MS) Resume.RESTORE else Resume.CONTINUE
    }

    fun encode(): String {
        val p = Properties()
        fun put(k: String, v: Any?) { if (v != null) p.setProperty(k, v.toString()) }
        put("phase", phase.name); put("startedAt", startedAt); put("endAt", endAt)
        put("pausedRemainingMs", pausedRemainingMs); put("minutes", minutes)
        put("seatReclined", seatReclined); put("passengers", passengers)
        put("placeName", placeName); put("applied", applied.joinToString(SEP)); put("savedAt", savedAt)
        with(snapshot) {
            put("s.backrest", backrest); put("s.climatePower", climatePower); put("s.temperature", temperature)
            put("s.fanSpeed", fanSpeed); put("s.recirculation", recirculation); put("s.window", window)
            put("s.cabinLightSwitch", cabinLightSwitch); put("s.ambientBrightness", ambientBrightness)
            put("s.ventilation", ventilation)
        }
        return StringWriter().also { p.store(it, null) }.toString()
    }

    companion object {
        /** 쉼표가 끝날 시각에서 이만큼 지났으면 이어 가지 않고 되돌리기만 한다. */
        const val STALE_MS = 30 * 60_000L
        private const val SEP = "\u001F"

        fun decode(text: String): RestSession? = runCatching {
            val p = Properties().apply { load(StringReader(text)) }
            fun s(k: String): String? = p.getProperty(k)
            RestSession(
                phase = Phase.valueOf(s("phase")!!),
                startedAt = s("startedAt")!!.toLong(),
                endAt = s("endAt")!!.toLong(),
                pausedRemainingMs = s("pausedRemainingMs")?.toLong(),
                minutes = s("minutes")!!.toInt(),
                seatReclined = s("seatReclined").toBoolean(),
                passengers = s("passengers").toBoolean(),
                placeName = s("placeName"),
                applied = s("applied")?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty(),
                savedAt = s("savedAt")!!.toLong(),
                snapshot = CabinSnapshot(
                    backrest = s("s.backrest")?.toInt(),
                    climatePower = s("s.climatePower")?.toBoolean(),
                    temperature = s("s.temperature")?.toFloat(),
                    fanSpeed = s("s.fanSpeed")?.toInt(),
                    recirculation = s("s.recirculation")?.toBoolean(),
                    window = s("s.window")?.toInt(),
                    cabinLightSwitch = s("s.cabinLightSwitch"),
                    ambientBrightness = s("s.ambientBrightness")?.toInt(),
                    ventilation = s("s.ventilation")?.toInt(),
                ),
            )
        }.getOrNull()
    }
}

/** 프로세스가 갑자기 끝나도 남도록 바로(commit) 쓴다. */
class SessionStore(private val prefs: SharedPreferences) {
    fun load(): RestSession? = prefs.getString(KEY, null)?.let(RestSession::decode)
    fun save(session: RestSession) { prefs.edit().putString(KEY, session.encode()).commit() }
    fun clear() { prefs.edit().remove(KEY).commit() }

    private companion object { const val KEY = "rest_session" }
}
