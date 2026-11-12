package app.parkrelay

import android.content.Context
import org.json.JSONObject

/** What the driver has told the app, kept on the car. Withdrawing consent clears all of it. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("parkrelay", Context.MODE_PRIVATE)

    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(value) = prefs.edit().putBoolean("consent", value).apply()

    var voiceQuestions: Boolean
        get() = prefs.getBoolean("voice_questions", true)
        set(value) = prefs.edit().putBoolean("voice_questions", value).apply()

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString("server_url", value).apply()

    /** On by default: a fresh install has no server to reach, and in this mode nothing leaves the car. */
    var demoMode: Boolean
        get() = prefs.getBoolean("demo_mode", true)
        set(value) = prefs.edit().putBoolean("demo_mode", value).apply()

    /** The lot last described to the driver. It lapses, so tomorrow's trip there is described again. */
    var advisedLot: String?
        get() = prefs.getString("advised_lot", null)
            ?.takeIf { System.currentTimeMillis() - prefs.getLong("advised_at", 0) < ADVICE_MEMORY_MILLIS }
        set(value) = prefs.edit().putString("advised_lot", value).putLong("advised_at", System.currentTimeMillis()).apply()

    fun confirmedLots(): Set<String> = prefs.getStringSet("confirmed_lots", emptySet()).orEmpty()

    fun rememberLot(lotId: String) {
        prefs.edit().putStringSet("confirmed_lots", confirmedLots() + lotId).apply()
    }

    fun sharePreference(lotId: String): Boolean? =
        if (prefs.contains("share_$lotId")) prefs.getBoolean("share_$lotId", false) else null

    fun rememberShare(lotId: String, allow: Boolean) {
        prefs.edit().putBoolean("share_$lotId", allow).apply()
    }

    fun sharePreferences(): Map<String, Boolean> =
        prefs.all.filterKeys { it.startsWith("share_") }
            .mapKeys { it.key.removePrefix("share_") }
            .mapValues { it.value == true }

    fun lastZone(lotId: String): String? = prefs.getString("zone_$lotId", null)

    fun saveCarLocation(lotId: String, lotName: String, zoneId: String?, atMillis: Long) {
        val editor = prefs.edit()
        if (zoneId != null) editor.putString("zone_$lotId", zoneId)
        editor.putString(
            "car_location",
            JSONObject().put("lotId", lotId).put("lot", lotName).put("zone", zoneId ?: JSONObject.NULL).put("at", atMillis).toString(),
        )
        editor.apply()
    }

    fun carLocation(): JSONObject? = prefs.getString("car_location", null)?.let(::JSONObject)

    fun clearAll() = prefs.edit().clear().apply()

    companion object {
        // The emulator reaches the machine it runs on at this address.
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8787"
        private const val ADVICE_MEMORY_MILLIS = 6 * 60 * 60_000L
    }
}
