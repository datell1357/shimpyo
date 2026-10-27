package app.shimpyo.core

import android.content.Context

data class RestRecord(val startedAtMillis: Long, val minutes: Int, val place: String?)

/** 휴식 기록은 차 안에만 저장한다(외부 전송 없음). */
class RestLog(context: Context) {
    private val prefs = context.getSharedPreferences("rest_log", Context.MODE_PRIVATE)

    fun add(record: RestRecord) {
        val line = listOf(record.startedAtMillis, record.minutes, record.place.orEmpty().replace('\t', ' '))
            .joinToString("\t")
        val all = (prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(200)
        prefs.edit().putString(KEY, all.joinToString("\n")).apply()
    }

    fun all(): List<RestRecord> = prefs.getString(KEY, "").orEmpty().lines().mapNotNull { line ->
        val c = line.split('\t')
        if (c.size < 3) return@mapNotNull null
        RestRecord(c[0].toLongOrNull() ?: return@mapNotNull null, c[1].toIntOrNull() ?: 0, c[2].ifBlank { null })
    }

    fun since(fromMillis: Long): List<RestRecord> = all().filter { it.startedAtMillis >= fromMillis }

    private companion object {
        const val KEY = "records"
    }
}
