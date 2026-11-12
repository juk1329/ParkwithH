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
 * The rules match server/board.py: a departure leaves a token for ten minutes, and a later parking
 * in that zone uses up the oldest one.
 */
class DemoBackend(private val capacityOf: (String) -> Int?) : Backend {
    private class Zone(var parked: Int = 0, var leaving: Int = 0, val tokens: MutableList<Long> = mutableListOf())

    private val handler = Handler(Looper.getMainLooper())
    private val lots = mutableMapOf<String, MutableMap<String, Zone>>()
    private val listeners = mutableMapOf<String, MutableList<(ZoneSignal) -> Unit>>()
    private val recent = ArrayDeque<String>()

    var onChanged: () -> Unit = {}

    override fun send(events: List<OutEvent>): Boolean {
        synchronized(this) { events.forEach(::record) }
        onChanged()
        return true
    }

    private fun record(event: OutEvent) {
        val zone = zones(event.lotId).getOrPut(event.zoneId ?: BoardText.NO_ZONE) { Zone() }
        when (event.type) {
            EventType.PARK -> {
                zone.parked++
                zone.tokens.removeFirstOrNull()
                note("내 차 주차", event.zoneId)
            }
            EventType.LEAVING_SOON -> {
                zone.leaving++
                note("내 차 곧 출차", event.zoneId)
            }
            EventType.LEAVING_CANCEL -> {
                zone.leaving = (zone.leaving - 1).coerceAtLeast(0)
                note("내 차 출차 취소", event.zoneId)
            }
            EventType.VACATED -> {
                zone.parked = (zone.parked - 1).coerceAtLeast(0)
                zone.leaving = (zone.leaving - 1).coerceAtLeast(0)
                zone.tokens.add(event.occurredAt)
                note("내 차 출차", event.zoneId)
            }
            EventType.EXIT -> note("내 차 주차장 이탈", event.zoneId)
        }
    }

    override fun subscribe(lotId: String, onSignal: (ZoneSignal) -> Unit): Backend.Subscription {
        synchronized(this) { listeners.getOrPut(lotId) { mutableListOf() }.add(onSignal) }
        return Backend.Subscription { synchronized(this) { listeners[lotId]?.remove(onSignal) } }
    }

    override fun board(lotId: String): LotBoard = synchronized(this) {
        val now = System.currentTimeMillis()
        val rows = zones(lotId).map { (id, zone) ->
            zone.tokens.removeAll { now - it > TOKEN_MILLIS }
            ZoneRow(id, zone.tokens.map { now - it }.sorted(), zone.leaving, zone.parked)
        }.sortedBy { it.zoneId }
        LotBoard(capacityOf(lotId), rows.sumOf { it.parkedAppCars }, rows)
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
        mutableMapOf("B1-A" to Zone(parked = 2), "B1-C" to Zone(parked = 1), "B2-B" to Zone(parked = 2))
    }

    private fun note(what: String, zoneId: String?) {
        recent.addFirst("$what · ${Guidance.spokenZone(zoneId) ?: "구역 미입력"}")
        while (recent.size > RECENT_LIMIT) recent.removeLast()
    }

    private companion object {
        const val TOKEN_MILLIS = 10 * 60_000L
        const val DEPARTURE_STEP_MILLIS = 6_000L
        const val RECENT_LIMIT = 6
    }
}
