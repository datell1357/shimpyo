package app.shimpyo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.shimpyo.vehicle.CabinSnapshot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** adb shell am broadcast -n app.shimpyo/.DebugReceiver --es cmd <recline|restore|snapshot|climate|window|dim|bright|breeze|cabin> */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ShimpyoApp
        val v = app.vehicle
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        MainScope().launch {
            val t0 = System.currentTimeMillis()
            val r: Any? = when (cmd) {
                "snapshot" -> v.snapshot().also { last = it }
                "recline" -> v.reclineDriverSeat()
                "restore" -> v.restoreDriverSeat(last)
                "climate" -> v.setRestClimate(last)
                "window" -> v.openWindowSlightly()
                "dim" -> v.dimCabin()
                "bright" -> v.brightenAmbient()
                "breeze" -> v.coolBreeze(last)
                "cabin" -> v.restoreCabin(last)
                "lock" -> v.lockDoors()
                "occupancy" -> v.passengersPresent()
                else -> "unknown"
            }
            Log.i("Shimpyo/Debug", "$cmd -> $r (${System.currentTimeMillis() - t0}ms)")
            pending.finish()
        }
    }

    companion object { var last = CabinSnapshot() }
}
