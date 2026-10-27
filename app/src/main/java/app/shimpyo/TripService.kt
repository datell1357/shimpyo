package app.shimpyo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.shimpyo.core.Phase
import app.shimpyo.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 쉼표가 내비 등 다른 앱 뒤에 있어도 연속 운전 계산·쉼터 도착·깨우기 타이머가 멈추지 않게 하는 포그라운드 서비스.
 * 백그라운드 앱은 몇 초 뒤 동결(freezer)되거나 메모리 부족으로 종료될 수 있다.
 */
class TripService : Service() {
    private val scope = MainScope()
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 앱 프로세스가 죽은 뒤 시스템이 서비스를 다시 띄울 때는 onStartCommand 없이 onCreate만 오는 경우가 있다
        ensureRunning("create")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureRunning("start #$startId")
        return START_STICKY
    }

    /** 포그라운드로 올리고, 흐름(쉼표 이어 가기 포함)을 시작하고, 단계에 맞춰 알림을 바꾼다. 여러 번 불려도 된다. */
    private fun ensureRunning(from: String) {
        val fg = goForeground((application as ShimpyoApp).controller.state.value.phase)
        Log.i(TAG, "$from foreground=$fg")
        val app = application as ShimpyoApp
        app.startIfPermitted()
        if (watchJob == null) {
            watchJob = scope.launch {
                app.controller.state.map { it.phase }.distinctUntilChanged().collect { goForeground(it) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(phase: Phase): Boolean = runCatching {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(phase), type)
        true
    }.onFailure { Log.w(TAG, "startForeground: ${it.message}") }.getOrDefault(false)

    private fun notification(phase: Phase): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "쉼표 지킴이", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (phase) {
            Phase.RESTING -> "쉼표 중 · 시간이 되면 천천히 깨워 드려요"
            Phase.WAKING -> "깨우는 중"
            Phase.DEPARTING -> "출발 준비"
            Phase.HEADING -> "졸음쉼터로 가는 중"
            else -> "연속 운전 시간을 보고 졸음쉼터를 알려 드려요"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "Shimpyo/Service"
        private const val CHANNEL = "trip"
        private const val NOTIFICATION_ID = 1

        /** 화면이 보일 때(포그라운드) 불러야 시작할 수 있다. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TripService::class.java)) }
                .onFailure { Log.w(TAG, "start: ${it.message}") }
        }
    }
}
