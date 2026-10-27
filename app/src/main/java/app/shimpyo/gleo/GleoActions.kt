package app.shimpyo.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.shimpyo.ShimpyoApp
import app.shimpyo.core.CommandResult
import app.shimpyo.core.RestAreaHit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Gleo에 돌려줄 결과(Callback Schema). message는 규격대로 영어. */
data class GleoReply(val status: String, val message: String, val data: Map<String, Any>? = null) {
    companion object {
        fun of(result: CommandResult): GleoReply = when (result) {
            is CommandResult.RestStarted ->
                GleoReply("success", "Started a ${result.minutes}-minute rest break and adjusted the seat and climate.")
            is CommandResult.Routed ->
                GleoReply("success", "Added ${result.hit.area.displayName}, ${km(result.hit)} km ahead, as a waypoint.", area(result.hit))
            is CommandResult.Found ->
                GleoReply("success", "The next drowsiness rest area is ${result.hit.area.displayName}, ${km(result.hit)} km ahead.", area(result.hit))
            CommandResult.AlreadyResting -> GleoReply("success", "A rest break is already in progress.")
            CommandResult.NoArea -> GleoReply("fail", "No drowsiness rest area was found ahead in the driving direction.")
            CommandResult.Waking -> GleoReply("success", "Ending the rest break and waking the driver gently.")
            CommandResult.NotResting -> GleoReply("fail", "There is no rest break in progress.")
        }

        private fun km(hit: RestAreaHit) = String.format(Locale.US, "%.1f", hit.distanceKm)

        private fun area(hit: RestAreaHit) = mapOf(
            "name" to hit.area.displayName, "distanceKm" to km(hit).toDouble(),
            "hasToilet" to hit.area.hasToilet, "route" to hit.area.routeName,
        )
    }
}

/**
 * Gleo AI 음성 명령(assets/schema.json의 actionCalls)을 받아 처리하고 결과를 돌려준다.
 * Gleo는 7초 안에 대답이 없으면 명령을 취소한다.
 */
object GleoActions {
    const val START_REST = "START_REST_BREAK"
    const val FIND_AREA = "FIND_REST_AREA"
    const val END_REST = "END_REST_BREAK"
    private const val EXTRA_PARAM = "ACTION_PARAM"
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    /** 앱이 막 켜졌을 때 차량 기능 확인을 기다리는 최대 시간(7초 제한 안). */
    private const val READY_WAIT_MS = 4_000L
    private const val TAG = "Shimpyo/Gleo"

    fun isGleo(intent: Intent?): Boolean = intent?.action in setOf(START_REST, FIND_AREA, END_REST)

    fun handle(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult? = null) {
        val app = context.applicationContext as ShimpyoApp
        val function = intent.action ?: return
        val param = intent.getStringExtra(EXTRA_PARAM)
        Log.i(TAG, "action $function $param")
        app.startIfPermitted()
        MainScope().launch {
            try {
                app.controller.awaitReady(READY_WAIT_MS)
                val c = app.controller
                val result = when (function) {
                    START_REST -> c.commandStartRest(minutes(param))
                    FIND_AREA -> c.commandFindArea()
                    else -> c.commandEndRest()
                }
                respond(app, function, GleoReply.of(result))
            } finally {
                pending?.finish()
            }
        }
    }

    private fun minutes(param: String?): Int? = runCatching {
        val v = JSONObject(param ?: "{}").optDouble("minutes")
        if (v.isNaN()) null else v.toInt()
    }.getOrNull()

    private fun respond(context: Context, function: String, reply: GleoReply) {
        runCatching {
            val json = JSONObject().apply {
                put("functionName", function)
                put("status", reply.status)
                put("message", reply.message)
                put("mode", "silent") // 안내는 쉼표가 한국어 음성으로 직접 한다
                reply.data?.let { put("data", JSONArray().put(JSONObject(it)).toString()) }
            }
            context.sendBroadcast(
                Intent(RESPONSE_ACTION).putExtra("packageName", context.packageName).putExtra("response", json.toString())
            )
            Log.i(TAG, "respond $json")
        }.onFailure { Log.w(TAG, "respond failed: ${it.message}") }
    }
}

/** 화면이 필요 없는 명령(component = service)은 브로드캐스트로 받는다. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (GleoActions.isGleo(intent)) GleoActions.handle(context, intent, goAsync())
    }
}
