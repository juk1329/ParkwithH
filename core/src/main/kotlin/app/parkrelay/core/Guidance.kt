package app.parkrelay.core

/**
 * The last few minutes of positions. It stays in the car: only the search time derived from it is
 * sent, and the route itself never leaves.
 */
class FixHistory(private val keepMillis: Long = 15 * 60 * 1000L) {
    private val fixes = ArrayDeque<Fix>()

    val last: Fix? get() = fixes.lastOrNull()

    fun add(fix: Fix) {
        fixes.addLast(fix)
        while (fixes.isNotEmpty() && fix.atMillis - fixes.first().atMillis > keepMillis) fixes.removeFirst()
    }

    /**
     * Seconds spent looking for a space: from first entering the lot's radius, or from the last
     * position before GPS was lost underground, until the car shifted to P.
     */
    fun searchSeconds(lot: Lot, parkedAt: Long): Long? {
        val entered = fixes.firstOrNull { distanceMeters(it.lat, it.lng, lot.lat, lot.lng) <= lot.radiusM }
            ?: return null
        return ((parkedAt - entered.atMillis) / 1000).coerceAtLeast(0)
    }
}

/** A signal about a lot, as received from the server by a car heading there. */
data class ZoneSignal(val type: EventType, val zoneId: String?, val weak: Boolean = false, val eventId: String? = null)

/**
 * The events this car sent recently. The server sends every car heading to a lot what happens there,
 * including what this car just did, and nobody should be told about their own departure.
 */
class OwnEvents(private val capacity: Int = 64) {
    private val ids = ArrayDeque<String>()

    fun add(eventId: String) {
        ids.addLast(eventId)
        while (ids.size > capacity) ids.removeFirst()
    }

    fun isOwn(signal: ZoneSignal): Boolean = signal.eventId != null && signal.eventId in ids
}

/**
 * Keeps guidance from turning into chatter: at most one sentence a minute. The exception is the
 * confirmation of a departure just announced as coming. It follows within seconds and is the
 * sentence a driver can act on, so the earlier warning must not silence it.
 */
class SpeechGate(private val minGapMillis: Long = 60_000L) {
    private var lastAt: Long? = null
    private var lastSignal: ZoneSignal? = null

    fun allow(signal: ZoneSignal, now: Long): Boolean {
        val spokenAt = lastAt
        val confirmsWarning = signal.type == EventType.VACATED &&
            lastSignal?.type == EventType.LEAVING_SOON &&
            lastSignal?.zoneId == signal.zoneId
        val allowed = spokenAt == null || now - spokenAt >= minGapMillis || confirmsWarning
        if (allowed) {
            lastAt = now
            lastSignal = signal
        }
        return allowed
    }
}

object Guidance {
    private val FLOOR_ZONE = Regex("""^(B|)(\d)(F?)(?:-(.+))?$""")

    /** "B2-B" becomes "지하 2층 B구역" for speech and notifications. */
    fun spokenZone(zoneId: String?): String? {
        val match = zoneId?.let { FLOOR_ZONE.matchEntire(it) } ?: return zoneId
        val (basement, floor, _, zone) = match.destructured
        val level = if (basement == "B") "지하 ${floor}층" else "${floor}층"
        return if (zone.isEmpty()) level else "$level ${zone}구역"
    }

    /** The sentence for a signal, or null when there is nothing a driver can use. Never promises a space. */
    fun sentence(signal: ZoneSignal): String? {
        val place = spokenZone(signal.zoneId)
        return when (signal.type) {
            EventType.VACATED ->
                if (place != null) "${place}에서 방금 1대 나갔어요." else "이 주차장에서 방금 1대 나갔어요."
            EventType.LEAVING_SOON ->
                if (place != null) "${place}에서 곧 나갈 차가 있어요." else null
            else -> null
        }
    }

    fun parkPromptTitle(zoneId: String?): String {
        val place = spokenZone(zoneId) ?: return "내 차 위치를 저장할까요?"
        return "${place}에 내 차 위치 저장"
    }

    fun parkQuestion(zoneId: String?): String {
        val place = spokenZone(zoneId) ?: return "지하 몇 층 어느 구역인가요?"
        return "${place}에 내 차 위치를 저장할까요?"
    }
}
