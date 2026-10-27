package app.shimpyo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shimpyo.ShimpyoApp

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { app.startOnce() }

    private val app get() = application as ShimpyoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 쉼표 타이머가 도는 동안 화면이 꺼지지 않게
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val missing = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) app.startOnce() else permissionLauncher.launch(missing.toTypedArray())

        val c = app.controller
        val actions = Actions(
            accept = c::acceptSuggestion,
            decline = c::declineSuggestion,
            restNow = c::restNow,
            startRest = c::startRest,
            wakeNow = c::wakeNow,
            extend = { c.extendRest(5) },
            depart = c::depart,
            setDemo = c::setDemo,
            demoAdd = c::demoAddDriving,
            simGear = c.sim?.let { sim -> sim::setGear },
            simBelt = c.sim?.let { sim -> sim::setSeatbelt },
        )
        setContent {
            ShimpyoTheme {
                val state by c.state.collectAsStateWithLifecycle()
                val spoken by c.voice.lastSpoken.collectAsStateWithLifecycle()
                val heard by c.voice.lastHeard.collectAsStateWithLifecycle()
                val listening by c.voice.listening.collectAsStateWithLifecycle()
                ShimpyoScreen(state, spoken, heard, listening, actions)
            }
        }
    }

    private companion object {
        val RUNTIME_PERMISSIONS = listOf(
            "pleos.car.permission.FUSED_LOCATION", // 보호 수준 dangerous
            Manifest.permission.ACCESS_FINE_LOCATION, // FusedLocation 미지원 환경의 대체 경로
        )
    }
}
