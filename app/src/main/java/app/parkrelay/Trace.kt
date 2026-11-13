package app.parkrelay

import android.util.Log

/** One log tag for the whole app, so tests and field checks can follow a flow with a single filter. */
object Trace {
    private const val TAG = "ParkRelay"

    fun log(area: String, message: String) {
        Log.i(TAG, "[$area] $message")
    }

    /**
     * For values that say where the car is or what was said in it: coordinates, destinations, lot
     * names, recognized speech. A debug build logs them for tests; a release build logs only that
     * there was one.
     */
    fun detail(value: Any?): String = if (BuildConfig.DEBUG) value.toString() else "(hidden)"

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
