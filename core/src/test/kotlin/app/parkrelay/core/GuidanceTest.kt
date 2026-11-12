package app.parkrelay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidanceTest {
    private val lot = Lot("L", "n", 37.5000, 127.0000, 500, ShareTier.AUTO, 150)

    @Test
    fun `zone ids become spoken Korean`() {
        assertEquals("지하 2층 B구역", Guidance.spokenZone("B2-B"))
        assertEquals("지하 3층", Guidance.spokenZone("B3"))
        assertEquals("3층 D구역", Guidance.spokenZone("3F-D"))
        assertEquals("지하 2층 12구역", Guidance.spokenZone("B2-12"))
        assertNull(Guidance.spokenZone(null))
    }

    @Test
    fun `a vacated signal states what happened and promises nothing`() {
        assertEquals(
            "지하 2층 B구역에서 방금 1대 나갔어요.",
            Guidance.sentence(ZoneSignal(EventType.VACATED, "B2-B")),
        )
        assertEquals("이 주차장에서 방금 1대 나갔어요.", Guidance.sentence(ZoneSignal(EventType.VACATED, null)))
    }

    @Test
    fun `only signals a driver can use produce speech`() {
        assertEquals("지하 2층 B구역에서 곧 나갈 차가 있어요.", Guidance.sentence(ZoneSignal(EventType.LEAVING_SOON, "B2-B")))
        assertNull(Guidance.sentence(ZoneSignal(EventType.LEAVING_SOON, null)))
        for (type in listOf(EventType.PARK, EventType.EXIT, EventType.LEAVING_CANCEL)) {
            assertNull(Guidance.sentence(ZoneSignal(type, "B2-B")))
        }
    }

    @Test
    fun `a car recognizes its own event and no one else's`() {
        val own = OwnEvents(capacity = 2)
        own.add("mine-1")
        assertTrue(own.isOwn(ZoneSignal(EventType.VACATED, "B2-B", eventId = "mine-1")))
        assertFalse(own.isOwn(ZoneSignal(EventType.VACATED, "B2-B", eventId = "theirs")))
        assertFalse(own.isOwn(ZoneSignal(EventType.VACATED, "B2-B")))
        own.add("mine-2")
        own.add("mine-3")
        assertFalse(own.isOwn(ZoneSignal(EventType.VACATED, "B2-B", eventId = "mine-1")))
    }

    @Test
    fun `the parking prompt reads as one phrase`() {
        assertEquals("지하 2층 B구역에 내 차 위치 저장", Guidance.parkPromptTitle("B2-B"))
        assertEquals("내 차 위치를 저장할까요?", Guidance.parkPromptTitle(null))
    }

    @Test
    fun `a departure confirmed seconds after its warning is still spoken`() {
        val gate = SpeechGate()
        assertTrue(gate.allow(ZoneSignal(EventType.LEAVING_SOON, "B2-B"), 0))
        assertTrue(gate.allow(ZoneSignal(EventType.VACATED, "B2-B"), 5_000))
    }

    @Test
    fun `other signals within a minute stay silent`() {
        val gate = SpeechGate()
        assertTrue(gate.allow(ZoneSignal(EventType.VACATED, "B2-B"), 0))
        assertFalse(gate.allow(ZoneSignal(EventType.VACATED, "B2-A"), 30_000))
        assertFalse(gate.allow(ZoneSignal(EventType.LEAVING_SOON, "B1-C"), 59_000))
        assertTrue(gate.allow(ZoneSignal(EventType.VACATED, "B2-A"), 60_000))
    }

    @Test
    fun `a warning does not unlock a departure in another zone`() {
        val gate = SpeechGate()
        assertTrue(gate.allow(ZoneSignal(EventType.LEAVING_SOON, "B2-B"), 0))
        assertFalse(gate.allow(ZoneSignal(EventType.VACATED, "B1-A"), 5_000))
    }

    @Test
    fun `search time runs from first entering the lot radius`() {
        val history = FixHistory()
        history.add(Fix(37.5200, 127.0000, 0))
        history.add(Fix(37.5005, 127.0000, 60_000))
        history.add(Fix(37.5001, 127.0000, 90_000))
        assertEquals(180, history.searchSeconds(lot, 240_000))
    }

    @Test
    fun `no fix inside the radius gives no search time`() {
        val history = FixHistory()
        history.add(Fix(37.5200, 127.0000, 0))
        assertNull(history.searchSeconds(lot, 240_000))
    }

    @Test
    fun `old positions are dropped so the route does not linger`() {
        val history = FixHistory(keepMillis = 1_000)
        history.add(Fix(37.5001, 127.0000, 0))
        history.add(Fix(37.5200, 127.0000, 5_000))
        assertNull(history.searchSeconds(lot, 6_000))
        assertTrue(history.last?.atMillis == 5_000L)
    }
}
