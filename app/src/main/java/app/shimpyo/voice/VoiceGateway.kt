package app.shimpyo.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import app.shimpyo.core.VoiceIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.speech.tts.TextToSpeech as AndroidTts

interface VoiceGateway {
    /** 화면 아래에 보여 줄 마지막 안내 문장. */
    val lastSpoken: StateFlow<String?>
    val lastHeard: StateFlow<String?>
    val listening: StateFlow<Boolean>

    /** 음성 인식을 쓸 수 있는지. */
    val canListen: Boolean

    suspend fun say(text: String)
    /** 한 번 듣고 들은 문장을 돌려준다. 못 들음·시간 초과·취소 → null. */
    suspend fun listen(timeoutMs: Long = 7_000): String?
    /** 예 → true, 아니오 → false, 못 알아들음·시간 초과 → null. */
    suspend fun askYesNo(timeoutMs: Long = 7_000): Boolean? = listen(timeoutMs)?.let { VoiceIntent.parse(it) }
    fun cancelListening()
    fun chime()
    fun start()
    fun stop()
}

/**
 * Pleos TextToSpeech·SpeechToText를 ON_DEVICE 모드로 쓴다(쉼터는 통신이 약할 수 있음, 앱 등록 불필요).
 * Pleos TTS가 안 되면 Android TTS로 대신 말한다.
 */
class PleosVoiceGateway(context: Context) : VoiceGateway {
    private val app = context.applicationContext
    private val _spoken = MutableStateFlow<String?>(null)
    private val _heard = MutableStateFlow<String?>(null)
    private val _listening = MutableStateFlow(false)
    override val lastSpoken: StateFlow<String?> = _spoken
    override val lastHeard: StateFlow<String?> = _heard
    override val listening: StateFlow<Boolean> = _listening

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** 안내가 겹치면 앞의 말이 끊기므로 한 번에 하나씩 말한다. */
    private val sayLock = Mutex()
    private var speakDone: CompletableDeferred<Unit>? = null

    private var stt: SpeechToText? = null
    private var sttReady = false
    private var answer: CompletableDeferred<String?>? = null

    private var androidTts: AndroidTts? = null
    private var androidReady = false
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 80) }.getOrNull() }

    private val ttsEvents = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { speakDone?.complete(Unit) }
        override fun onStop() { speakDone?.complete(Unit) }
        override fun onError(errorType: TtsError) {
            Log.w(TAG, "tts error $errorType")
            speakDone?.completeExceptionally(IllegalStateException(errorType.toString()))
        }
    }

    private val sttEvents = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() { _listening.value = true }
        override fun onUpdated(stt: String, completed: Boolean) {
            _heard.value = stt
            if (completed) answer?.complete(stt)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { _listening.value = false; answer?.complete(_heard.value) }
        override fun onError(errorType: SttError) {
            Log.w(TAG, "stt error $errorType")
            _listening.value = false
            answer?.complete(null)
        }
    }

    override fun start() {
        runCatching {
            val t = TextToSpeech(app, TtsMode.ON_DEVICE)
            t.initialize(object : TtsInit {
                override fun onSuccess() {
                    Log.i(TAG, "tts ready")
                    ttsReady = true
                    runCatching { t.addEventListener(ttsEvents); t.setLocale(Locale.KOREA) }
                }
                override fun onFailure(errorType: TtsError) { Log.w(TAG, "tts init $errorType") }
            })
            tts = t
        }.onFailure { Log.w(TAG, "tts create: ${it.message}") }

        runCatching {
            val s = SpeechToText(app, SttMode.ON_DEVICE)
            s.initialize(object : SttInit {
                override fun onSuccess() {
                    Log.i(TAG, "stt ready")
                    sttReady = true
                    runCatching { s.addListener(sttEvents); s.setLocale(Locale.KOREA) }
                }
                override fun onFailure(errorType: SttError) { Log.w(TAG, "stt init $errorType") }
            })
            stt = s
        }.onFailure { Log.w(TAG, "stt create: ${it.message}") }

        androidTts = AndroidTts(app) { status ->
            androidReady = status == AndroidTts.SUCCESS
            if (androidReady) androidTts?.language = Locale.KOREAN
        }
    }

    override fun stop() {
        runCatching { tts?.removeEventListener(ttsEvents); tts?.release() }
        runCatching { stt?.removeListener(sttEvents); stt?.release() }
        runCatching { androidTts?.shutdown() }
        runCatching { tone?.release() }
    }

    override suspend fun say(text: String) = sayLock.withLock { speak(text) }

    private suspend fun speak(text: String) {
        _spoken.value = text
        val budget = 1_500L + text.length * 120L
        val t = tts
        if (t != null && ttsReady) {
            val done = CompletableDeferred<Unit>().also { speakDone = it }
            val t0 = System.currentTimeMillis()
            val ok = runCatching { t.speak(text) }.isSuccess &&
                withTimeoutOrNull(budget) { runCatching { done.await() }.isSuccess } == true
            Log.i(TAG, "say(pleos) ok=$ok ${System.currentTimeMillis() - t0}ms: $text")
            if (ok) return
        }
        val a = androidTts
        if (a != null && androidReady) {
            Log.i(TAG, "say(android): $text")
            a.speak(text, AndroidTts.QUEUE_FLUSH, null, "shimpyo")
            delay(budget.coerceAtMost(8_000))
        } else {
            delay(1_200) // 음성이 없어도 화면 글자를 읽을 시간
        }
    }

    override val canListen: Boolean get() = stt != null && sttReady

    override suspend fun listen(timeoutMs: Long): String? {
        val s = stt
        if (s == null || !sttReady) return null
        _heard.value = null
        val pending = CompletableDeferred<String?>().also { answer = it }
        if (runCatching { s.request() }.isFailure) return null
        _listening.value = true
        val text = withTimeoutOrNull(timeoutMs) { pending.await() }
        runCatching { s.stop() }
        _listening.value = false
        return text
    }

    override fun cancelListening() {
        answer?.complete(null)
        runCatching { stt?.stop() }
        _listening.value = false
    }

    override fun chime() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
    }

    private companion object { const val TAG = "Shimpyo/Voice" }
}
