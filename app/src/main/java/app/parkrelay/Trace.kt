package app.parkrelay

import android.util.Log

/** One log tag for the whole app, so tests and field checks can follow a flow with a single filter. */
object Trace {
    private const val TAG = "ParkRelay"

    fun log(area: String, message: String) {
        Log.i(TAG, "[$area] $message")
    }

    /**
     * Runs a call that touches a platform service. A service can be missing on some car models,
     * so a failure here must degrade one feature and never take down the parking service.
     */
    inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("error", "$what failed: $e")
        }
    }
}
