package app.parkrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts detection when the car's system boots, so the driver never has to open the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Trace.log("boot", "boot completed")
        Trace.guarded("boot start") { ParkingService.start(context) }
    }
}
