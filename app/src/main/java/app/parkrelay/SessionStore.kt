package app.parkrelay

import android.content.Context
import app.parkrelay.core.Confidence
import app.parkrelay.core.Gear
import app.parkrelay.core.LotCatalog
import app.parkrelay.core.Phase
import app.parkrelay.core.SessionState
import org.json.JSONObject
import java.io.File

/**
 * Saves the session after every step. The app can stop at any moment once the ignition is off,
 * and the next start has to know whether this car is parked somewhere.
 */
class SessionStore(context: Context, private val catalog: LotCatalog) {
    private val file = File(context.filesDir, "session.json")
    private val temp = File(context.filesDir, "session.json.tmp")

    fun load(): SessionState {
        if (!file.exists()) return SessionState()
        return try {
            decode(JSONObject(file.readText()))
        } catch (e: Exception) {
            Trace.log("store", "unreadable session, starting clean: $e")
            SessionState()
        }
    }

    /** Writes through a temporary file, so power loss cannot leave half a session behind. */
    fun save(state: SessionState) {
        Trace.guarded("store save") {
            temp.writeText(encode(state).toString())
            temp.renameTo(file)
        }
    }

    private fun encode(s: SessionState) = JSONObject()
        .put("phase", s.phase.name)
        .put("gear", s.gear.name)
        .putOpt("reverseAt", s.reverseAt)
        .putOpt("parkSince", s.parkSince)
        .putOpt("lotId", s.lot?.id)
        .put("confidence", s.confidence.name)
        .putOpt("zoneId", s.zoneId)
        .putOpt("sessionId", s.sessionId)
        .putOpt("searchSeconds", s.searchSeconds)
        .put("shareAllowed", s.shareAllowed)
        .put("parkPublished", s.parkPublished)
        .putOpt("driverSeatOccupied", s.driverSeatOccupied)
        .put("passengerDoorOpen", s.passengerDoorOpen)
        .putOpt("departedAt", s.departedAt)
        .put("movedMeters", s.movedMeters)
        .put("speedSeen", s.speedSeen)
        .put("wokeSinceParked", s.wokeSinceParked)
        .put("weakSignalSent", s.weakSignalSent)

    private fun decode(j: JSONObject) = SessionState(
        phase = Phase.valueOf(j.getString("phase")),
        gear = Gear.valueOf(j.getString("gear")),
        reverseAt = j.longOrNull("reverseAt"),
        parkSince = j.longOrNull("parkSince"),
        lot = j.optString("lotId").takeIf { it.isNotEmpty() }?.let(catalog::byId),
        confidence = Confidence.valueOf(j.getString("confidence")),
        zoneId = j.optString("zoneId").takeIf { it.isNotEmpty() },
        sessionId = j.optString("sessionId").takeIf { it.isNotEmpty() },
        searchSeconds = j.longOrNull("searchSeconds"),
        shareAllowed = j.optBoolean("shareAllowed"),
        parkPublished = j.optBoolean("parkPublished"),
        driverSeatOccupied = if (j.has("driverSeatOccupied")) j.getBoolean("driverSeatOccupied") else null,
        passengerDoorOpen = j.optBoolean("passengerDoorOpen"),
        departedAt = j.longOrNull("departedAt"),
        movedMeters = j.optDouble("movedMeters", 0.0),
        speedSeen = j.optBoolean("speedSeen"),
        wokeSinceParked = j.optBoolean("wokeSinceParked"),
        weakSignalSent = j.optBoolean("weakSignalSent"),
    )

    private fun JSONObject.longOrNull(key: String): Long? = if (has(key)) getLong(key) else null
}

/** Loads the catalog bundled with the app. */
fun loadCatalog(context: Context): LotCatalog =
    context.assets.open("lots.tsv").bufferedReader().useLines { LotCatalog.parse(it) }
