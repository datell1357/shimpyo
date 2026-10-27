package app.shimpyo.core

/** 갑자기 깨우지 않고 빛 → 바람 → 자세 → 소리 순서로. */
enum class WakeStep(val label: String) {
    LIGHT("조명이 서서히 밝아져요"),
    AIR("시원한 바람과 시트 통풍"),
    SEAT("시트가 원래 자리로"),
    ALARM("일어날 시간이에요"),
}

object WakePlan {
    /** 끝나기 몇 분 전에 시작하는지. */
    private val offsetsMin = linkedMapOf(
        WakeStep.LIGHT to 2.0,
        WakeStep.AIR to 1.0,
        WakeStep.SEAT to 0.5,
        WakeStep.ALARM to 0.0,
    )

    /** 아직 실행하지 않은 단계 중 지금 실행할 단계들(순서대로). */
    fun dueSteps(remainingMillis: Long, done: Set<WakeStep>, minuteMillis: Long): List<WakeStep> =
        offsetsMin.filter { (step, before) ->
            step !in done && remainingMillis <= (before * minuteMillis).toLong()
        }.keys.toList()
}

/** 깨운 뒤 "깨셨어요?"에 대한 대답. */
enum class WakeAnswer { AWAKE, MORE }

/** 짧은 대답이 예/아니오인지. */
object VoiceIntent {
    private val no = listOf("아니", "괜찮", "나중", "됐어", "싫어", "안 쉬", "안쉬", "그냥 갈")
    private val yesWords = setOf("응", "어", "네", "예", "엉", "ㅇㅇ", "ok")
    private val yesParts = listOf("그래", "좋아", "쉬어", "쉴게", "갈게", "들를", "시작", "오케이", "부탁")
    // 깨우기: "아직 안 깼어"처럼 둘 다 들어 있으면 더 쉬는 쪽으로
    private val moreParts = listOf("더 ", "더잘", "졸려", "졸리", "조금만", "분만", "아직", "아니", "싫어", "피곤")
    private val awakeParts = listOf("깼", "일어났", "일어날", "괜찮", "좋아", "출발", "개운")

    private fun normalize(text: String) = text.trim().lowercase().trimEnd('.', '!', '?', '요')

    fun parse(text: String): Boolean? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        if (no.any { t.contains(it) }) return false
        if (t.split(' ', ',').any { it in yesWords }) return true
        if (yesParts.any { t.contains(it) }) return true
        return null
    }

    fun parseWake(text: String): WakeAnswer? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        if (moreParts.any { "$t ".contains(it) }) return WakeAnswer.MORE
        if (awakeParts.any { t.contains(it) } || t.split(' ', ',').any { it in yesWords }) return WakeAnswer.AWAKE
        return null
    }
}

/** 받침에 맞는 조사. */
object Josa {
    private fun hasBatchim(word: String): Boolean {
        val c = word.trim().lastOrNull() ?: return false
        if (c !in '가'..'힣') return false
        return (c - '가') % 28 != 0
    }

    private fun hasRieulBatchim(word: String): Boolean {
        val c = word.trim().lastOrNull() ?: return false
        return c in '가'..'힣' && (c - '가') % 28 == 8
    }

    fun eulReul(word: String) = word + if (hasBatchim(word)) "을" else "를"

    /** ㄹ 받침이거나 받침이 없으면 '로', 그 외 받침은 '으로'. */
    fun ro(word: String) = word + if (hasBatchim(word) && !hasRieulBatchim(word)) "으로" else "로"
}
