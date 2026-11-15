package app.parkrelay

import android.os.Handler
import android.os.Looper
import app.parkrelay.core.BoardText
import app.parkrelay.core.EventType
import app.parkrelay.core.Guidance
import app.parkrelay.core.LotBoard
import app.parkrelay.core.OutEvent
import app.parkrelay.core.ZoneRow
import app.parkrelay.core.ZoneSignal

/**
 * Stands in for the server and for other cars, so one emulator with this APK and no server can show
 * the whole flow. Nothing leaves the car. The screen always says this mode is on, because the other
 * cars and the zone names here are made up.
 *
 * The rules match server/board.py: a departure leaves a token for ten minutes, a later parking in
 * that zone uses up the oldest one, and a car that said where it is heading counts beside the
 * departures of that zone until it parks, cancels, or ten minutes pass.
 */
class DemoBackend(private val capacityOf: (String) -> Int?) : Backend {
    private class Zone(var parked: Int = 0, var leaving: Int = 0, val tokens: MutableList<Long> = mutableListOf())
    private class Heading(val zoneId: String, val at: Long, val confirmed: Boolean = false)

    private val handler = Handler(Looper.getMainLooper())
    private val lots = mutableMapOf<String, MutableMap<String, Zone>>()
    private val claims = mutableMapOf<String, MutableMap<String, Heading>>()
    private val parkedSessions = mutableMapOf<String, String>()
    private val listeners = mutableMapOf<String, MutableList<(ZoneSignal) -> Unit>>()
    private val recent = ArrayDeque<String>()

    var onChanged: () -> Unit = {}

    override fun send(events: List<OutEvent>): Delivery {
        synchronized(this) { events.forEach(::record) }
        // The server tells every car watching the lot, the sender included, so the same happens here.
        handler.post {
            events.forEach { emit(it.lotId, ZoneSignal(it.type, it.zoneId, it.weak, it.eventId)) }
        }
        return Delivery.ACCEPTED
    }

    private fun record(event: OutEvent) {
        when (event.type) {
            EventType.CLAIM -> {
                val zoneId = event.zoneId ?: return
                claims.getOrPut(event.lotId) { mutableMapOf() }[event.sessionId] = Heading(zoneId, event.occurredAt)
                note("내 차 가는 중 알림", zoneId)
                return
            }
            EventType.CLAIM_CONFIRM -> {
                val zoneId = event.zoneId ?: return
                claims.getOrPut(event.lotId) { mutableMapOf() }[event.sessionId] = Heading(zoneId, event.occurredAt, confirmed = true)
                note("내 차 도착, 구역 확정", zoneId)
                return
            }
            EventType.CLAIM_MISSED -> {
                claims[event.lotId]?.remove(event.sessionId)
                // The driver found no space: whatever departures were listed there are stale.
                event.zoneId?.let { zones(event.lotId)[it]?.tokens?.clear() }
                note("내 차 자리 없음, 확정 취소", event.zoneId)
                return
            }
            EventType.CLAIM_CANCEL -> {
                claims[event.lotId]?.remove(event.sessionId)
                note("내 차 가는 중 해제", event.zoneId)
                return
            }
            else -> Unit
        }
        val zoneId = event.zoneId ?: BoardText.NO_ZONE
        val zone = zones(event.lotId).getOrPut(zoneId) { Zone() }
        when (event.type) {
            EventType.PARK -> {
                // A repeat only restates or corrects where this session is; it is one car all along.
                val before = parkedSessions.put(event.sessionId, zoneId)
                if (before == zoneId) return
                if (before != null) zones(event.lotId)[before]?.let { it.parked = (it.parked - 1).coerceAtLeast(0) }
                zone.parked++
                zone.tokens.removeFirstOrNull()
                note("내 차 주차", event.zoneId)
            }
            EventType.LEAVING_SOON -> {
                if (!event.weak) zone.leaving++
                note("내 차 곧 출차", event.zoneId)
            }
            EventType.LEAVING_CANCEL -> {
                zone.leaving = (zone.leaving - 1).coerceAtLeast(0)
                note("내 차 출차 취소", event.zoneId)
            }
            EventType.VACATED -> {
                parkedSessions.remove(event.sessionId)
                zone.parked = (zone.parked - 1).coerceAtLeast(0)
                zone.leaving = (zone.leaving - 1).coerceAtLeast(0)
                zone.tokens.add(event.occurredAt)
                note("내 차 출차", event.zoneId)
            }
            EventType.EXIT -> note("내 차 주차장 이탈", event.zoneId)
            EventType.CLAIM, EventType.CLAIM_CANCEL, EventType.CLAIM_CONFIRM, EventType.CLAIM_MISSED -> Unit
        }
    }

    override fun subscribe(lotId: String, onSignal: (ZoneSignal) -> Unit): Backend.Subscription {
        synchronized(this) { listeners.getOrPut(lotId) { mutableListOf() }.add(onSignal) }
        return Backend.Subscription { synchronized(this) { listeners[lotId]?.remove(onSignal) } }
    }

    override fun board(lotId: String): LotBoard = synchronized(this) {
        val now = System.currentTimeMillis()
        val heading = claims[lotId].orEmpty().values.filter { now - it.at <= TOKEN_MILLIS }
        val rows = zones(lotId).map { (id, zone) ->
            zone.tokens.removeAll { now - it > TOKEN_MILLIS }
            ZoneRow(id, zone.tokens.map { now - it }.sorted(), zone.leaving, zone.parked,
                heading.count { it.zoneId == id }, heading.count { it.zoneId == id && it.confirmed })
        }
        LotBoard(capacityOf(lotId), rows.sumOf { it.parkedAppCars }, BoardText.inFloorOrder(rows))
    }

    /**
     * Sets the scene a car finds a hundred metres from a busy lot: departures on two floors, one of
     * them already with a car on its way, and a car about to pull out. Seeded at the press of the
     * demo button, so the departures are fresh whenever the demo is run.
     */
    fun seedApproach(lotId: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val zones = zones(lotId)
            if (zones.values.any { zone -> zone.tokens.any { now - it < SEED_FRESH_MILLIS } }) return
            zones.getOrPut("B1-A") { Zone() }.tokens.add(now - 4 * MINUTE)
            zones.getOrPut("B1-C") { Zone() }.tokens.add(now - 2 * MINUTE)
            zones.getOrPut("B2-B") { Zone() }.tokens.addAll(listOf(now - 3 * MINUTE, now - 1 * MINUTE))
            zones.getOrPut("B3-C") { Zone() }.leaving = 1
            claims.getOrPut(lotId) { mutableMapOf() }[CAR_AHEAD] = Heading("B1-A", now - 30_000)
            note("다른 차 출차", "B1-A")
            note("다른 차 출차", "B1-C")
            note("다른 차 출차 2대", "B2-B")
            note("앞차 가는 중", "B1-A")
            note("다른 차 곧 출차", "B3-C")
        }
        onChanged()
    }

    /** Another car pulls out: first the gear leaves P, and a moment later it has moved away. */
    fun simulateDeparture(lotId: String, zoneId: String, afterMillis: Long) {
        handler.postDelayed({
            synchronized(this) {
                zones(lotId).getOrPut(zoneId) { Zone() }.leaving++
                note("다른 차 곧 출차", zoneId)
            }
            emit(lotId, ZoneSignal(EventType.LEAVING_SOON, zoneId))
        }, afterMillis)
        handler.postDelayed({
            synchronized(this) {
                val zone = zones(lotId).getOrPut(zoneId) { Zone() }
                zone.leaving = (zone.leaving - 1).coerceAtLeast(0)
                zone.parked = (zone.parked - 1).coerceAtLeast(0)
                zone.tokens.add(System.currentTimeMillis())
                note("다른 차 출차", zoneId)
            }
            emit(lotId, ZoneSignal(EventType.VACATED, zoneId))
        }, afterMillis + DEPARTURE_STEP_MILLIS)
    }

    fun recentActivity(): List<String> = synchronized(this) { recent.toList() }

    private fun emit(lotId: String, signal: ZoneSignal) {
        val targets = synchronized(this) { listeners[lotId].orEmpty().toList() }
        targets.forEach { it(signal) }
        onChanged()
    }

    private fun zones(lotId: String) = lots.getOrPut(lotId) {
        mutableMapOf("B1-A" to Zone(parked = 2), "B1-C" to Zone(parked = 1), "B2-B" to Zone(parked = 3))
    }

    private fun note(what: String, zoneId: String?) {
        recent.addFirst("$what · ${Guidance.spokenZone(zoneId) ?: "구역 미입력"}")
        while (recent.size > RECENT_LIMIT) recent.removeLast()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val TOKEN_MILLIS = 10 * MINUTE
        const val SEED_FRESH_MILLIS = 5 * MINUTE
        const val DEPARTURE_STEP_MILLIS = 6_000L
        const val RECENT_LIMIT = 6
        const val CAR_AHEAD = "demo-car-ahead"
    }
}
