package app.parkrelay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walks the transition table and attack scenarios in the design doc, section 8. */
class SessionReducerTest {
    private val publicLot = Lot("L1", "세종로 공영주차장", 37.57, 126.97, 1260, ShareTier.AUTO, 300)
    private val askLot = Lot("L2", "코엑스 주차장", 37.51, 127.05, 2123, ShareTier.ASK, 300)

    private fun harness() = Harness(SessionReducer(newId = counter()))

    private fun counter(): () -> String {
        var n = 0
        return { "id-${++n}" }
    }

    private class Harness(private val reducer: SessionReducer) {
        var state = SessionState()
        val published = mutableListOf<OutEvent>()
        val effects = mutableListOf<Effect>()

        fun feed(vararg inputs: Input): Harness {
            for (input in inputs) {
                val step = reducer.reduce(state, input)
                state = step.state
                effects += step.effects
                published += step.effects.filterIsInstance<Effect.Publish>().map { it.event }
            }
            return this
        }

        fun types() = published.map { it.type }
    }

    private fun single(lot: Lot, confidence: Confidence = Confidence.HIGH) = LotResolution.Single(lot, confidence)

    /** Reverse into a space and shift to P in a lot identified with high confidence. */
    private fun Harness.parkInto(lot: Lot, at: Long = 0, zone: String? = "B2-B"): Harness {
        feed(
            Input.GearChanged(Gear.REVERSE, at),
            Input.GearChanged(Gear.PARK, at + 1_000),
            Input.Resolved(single(lot), 90, at + 1_100),
            Input.Tick(at + 4_100),
        )
        assertEquals(Phase.P_TENTATIVE, state.phase)
        feed(Input.ZoneAnswered(zone, at + 5_000))
        assertEquals(Phase.PARKED, state.phase)
        return this
    }

    @Test
    fun `reverse before park shortens the hold to three seconds`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.Tick(3_000),
        )
        assertEquals(Phase.DRIVING, h.state.phase)
        h.feed(Input.Tick(4_000))
        assertEquals(Phase.P_TENTATIVE, h.state.phase)
    }

    @Test
    fun `park without reverse waits ten seconds`() {
        val h = harness().feed(
            Input.GearChanged(Gear.DRIVE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.Tick(10_000),
        )
        assertEquals(Phase.DRIVING, h.state.phase)
        h.feed(Input.Tick(11_000))
        assertEquals(Phase.P_TENTATIVE, h.state.phase)
    }

    // The platform timer runs on uptime and the reducer compares wall-clock time, so a wake can come a
    // moment early. Each of these waits has no other wake coming, so a lost one would stall the session.
    @Test
    fun `a wake a moment early for the hold is not lost`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
        )
        h.effects.clear()
        h.feed(Input.Tick(3_999))
        assertEquals(Phase.DRIVING, h.state.phase)
        assertEquals(listOf<Effect>(Effect.WakeAt(4_000)), h.effects)
        h.feed(Input.Tick(4_000))
        assertEquals(Phase.P_TENTATIVE, h.state.phase)
    }

    @Test
    fun `a wake a moment early for the unanswered question is not lost`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.Tick(4_100),
        )
        assertEquals(Phase.P_TENTATIVE, h.state.phase)
        h.effects.clear()
        h.feed(Input.Tick(120_999))
        assertEquals(Phase.P_TENTATIVE, h.state.phase)
        assertEquals(listOf<Effect>(Effect.WakeAt(121_000)), h.effects)
        h.feed(Input.Tick(121_000))
        assertEquals(Phase.PARKED, h.state.phase)
    }

    @Test
    fun `a wake a moment early for a departure with no speed signal is not lost`() {
        val h = harness().parkInto(publicLot).feed(Input.GearChanged(Gear.DRIVE, 100_000))
        assertEquals(Phase.LEAVING, h.state.phase)
        h.effects.clear()
        h.feed(Input.Tick(159_999))
        assertEquals(Phase.LEAVING, h.state.phase)
        assertEquals(listOf<Effect>(Effect.WakeAt(160_000)), h.effects)
        h.feed(Input.Tick(160_000))
        assertEquals(Phase.VACATED, h.state.phase)
    }

    @Test
    fun `a reverse long before parking does not shorten the hold`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.DRIVE, 1_000),
            Input.GearChanged(Gear.PARK, 100_000),
            Input.Resolved(single(publicLot), null, 100_100),
            Input.Tick(104_000),
        )
        assertEquals(Phase.DRIVING, h.state.phase)
    }

    @Test
    fun `no lot means no session, no prompt, and nothing sent`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(LotResolution.None, null, 1_100),
            Input.Tick(60_000),
            Input.DriverDoor(true, 61_000),
            Input.Tick(500_000),
            Input.GearChanged(Gear.DRIVE, 900_000),
        )
        assertEquals(Phase.DRIVING, h.state.phase)
        assertTrue(h.published.isEmpty())
        assertTrue(h.effects.none { it is Effect.PromptPark })
    }

    @Test
    fun `leaving park before confirmation sends nothing`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.Tick(4_100),
            Input.GearChanged(Gear.DRIVE, 20_000),
        )
        assertEquals(Phase.DRIVING, h.state.phase)
        assertTrue(h.published.isEmpty())
        assertTrue(h.effects.any { it is Effect.CancelPrompt })
    }

    @Test
    fun `drop-off with the driver seated never produces a false vacancy`() {
        val h = harness().feed(
            Input.GearChanged(Gear.DRIVE, 0),
            Input.DriverSeat(true, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.Tick(11_000),
            Input.PassengerDoor(true, 15_000),
            Input.PassengerDoor(false, 25_000),
            Input.Tick(300_000),
            Input.GearChanged(Gear.DRIVE, 310_000),
            Input.Speed(5f, 311_000),
            Input.Speed(5f, 316_000),
        )
        assertEquals(Phase.DRIVING, h.state.phase)
        assertTrue(h.published.isEmpty())
    }

    @Test
    fun `driver door opening commits the parking`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), 45, 1_100),
            Input.Tick(4_100),
            Input.DriverDoor(true, 5_000),
        )
        assertEquals(Phase.PARKED, h.state.phase)
        assertEquals(listOf(EventType.PARK), h.types())
        assertNull(h.published.single().zoneId)
        assertEquals(45, h.published.single().searchSeconds)
    }

    @Test
    fun `driver door opening during the hold still commits`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.DriverDoor(true, 1_500),
        )
        assertEquals(Phase.PARKED, h.state.phase)
        assertEquals(listOf(EventType.PARK), h.types())
    }

    @Test
    fun `two minutes without an answer commits when the seat is not occupied`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.Tick(4_100),
            Input.Tick(120_000),
        )
        assertEquals(Phase.P_TENTATIVE, h.state.phase)
        h.feed(Input.Tick(121_000))
        assertEquals(Phase.PARKED, h.state.phase)
    }

    @Test
    fun `a low confidence lot is not shared until the driver confirms it`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot, Confidence.LOW), null, 1_100),
            Input.Tick(4_100),
            Input.DriverDoor(true, 5_000),
        )
        assertEquals(Phase.PARKED, h.state.phase)
        assertTrue(h.published.isEmpty())
        h.feed(Input.ZoneAnswered("B1-A", 400_000))
        assertEquals(listOf(EventType.PARK), h.types())
        assertEquals("B1-A", h.published.single().zoneId)
    }

    @Test
    fun `an unconfirmed session stays silent all the way out`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot, Confidence.LOW), null, 1_100),
            Input.Tick(4_100),
            Input.DriverDoor(true, 5_000),
            Input.GearChanged(Gear.DRIVE, 100_000),
            Input.LeftLot(200_000),
        )
        assertEquals(Phase.DRIVING, h.state.phase)
        assertTrue(h.published.isEmpty())
    }

    @Test
    fun `an ask tier lot shares nothing by default but still saves the car location`() {
        val h = harness().parkInto(askLot)
        assertTrue(h.published.isEmpty())
        assertTrue(h.effects.any { it is Effect.SaveCarLocation })
        assertTrue(h.effects.filterIsInstance<Effect.PromptPark>().single().askShare)
    }

    @Test
    fun `an ask tier lot shares after the driver agrees`() {
        val h = harness().parkInto(askLot).feed(Input.ShareAnswered(true, 6_000))
        assertEquals(listOf(EventType.PARK), h.types())
        assertTrue(h.effects.contains(Effect.RememberShare(askLot.id, true)))
    }

    @Test
    fun `declining to share stops every later event`() {
        val h = harness().parkInto(publicLot)
        val before = h.published.size
        h.feed(
            Input.ShareAnswered(false, 6_000),
            Input.GearChanged(Gear.DRIVE, 100_000),
            Input.Tick(161_000),
            Input.LeftLot(200_000),
        )
        assertEquals(before, h.published.size)
    }

    @Test
    fun `without consent nothing is shared`() {
        val reducer = SessionReducer(newId = counter(), consent = { false })
        val h = Harness(reducer).parkInto(publicLot)
        assertTrue(h.published.isEmpty())
    }

    @Test
    fun `a remembered yes shares an ask tier lot without asking again`() {
        val reducer = SessionReducer(newId = counter(), sharePreference = { true })
        val h = Harness(reducer).parkInto(askLot)
        assertEquals(listOf(EventType.PARK), h.types())
        assertFalse(h.effects.filterIsInstance<Effect.PromptPark>().single().askShare)
    }

    @Test
    fun `a remembered no is respected even in an auto tier lot`() {
        val reducer = SessionReducer(newId = counter(), sharePreference = { false })
        val h = Harness(reducer).parkInto(publicLot)
        assertTrue(h.published.isEmpty())
    }

    @Test
    fun `leaving park publishes immediately and the space opened at that moment`() {
        val h = harness().parkInto(publicLot).feed(
            Input.GearChanged(Gear.REVERSE, 100_000),
            Input.Speed(2f, 100_500),
            Input.Speed(2f, 104_500),
            Input.Speed(2f, 108_500),
        )
        assertEquals(listOf(EventType.PARK, EventType.LEAVING_SOON, EventType.VACATED), h.types())
        assertEquals(Phase.VACATED, h.state.phase)
        val vacated = h.published.last()
        assertEquals(100_000, vacated.occurredAt)
        assertEquals(Evidence.SPEED, vacated.evidence)
        assertEquals("B2-B", vacated.zoneId)
    }

    @Test
    fun `without a speed signal sixty seconds out of park confirms the departure`() {
        val h = harness().parkInto(publicLot).feed(
            Input.GearChanged(Gear.DRIVE, 100_000),
            Input.Tick(159_000),
        )
        assertEquals(Phase.LEAVING, h.state.phase)
        h.feed(Input.Tick(160_000))
        assertEquals(Phase.VACATED, h.state.phase)
        assertEquals(Evidence.GEAR_TIME, h.published.last().evidence)
    }

    @Test
    fun `a speed sensor reading zero does not count as leaving on a timer`() {
        val h = harness().parkInto(publicLot).feed(
            Input.Speed(0f, 90_000),
            Input.GearChanged(Gear.DRIVE, 100_000),
            Input.Speed(0f, 101_000),
            Input.Tick(300_000),
        )
        assertEquals(Phase.LEAVING, h.state.phase)
    }

    @Test
    fun `shifting back to park after a small move cancels the departure`() {
        val h = harness().parkInto(publicLot).feed(
            Input.GearChanged(Gear.REVERSE, 100_000),
            Input.Speed(1f, 100_500),
            Input.Speed(1f, 103_500),
            Input.GearChanged(Gear.PARK, 104_000),
        )
        assertEquals(Phase.PARKED, h.state.phase)
        assertEquals(EventType.LEAVING_CANCEL, h.published.last().type)
    }

    @Test
    fun `a huge gap in speed samples cannot invent a departure`() {
        val h = harness().parkInto(publicLot).feed(
            Input.GearChanged(Gear.DRIVE, 100_000),
            Input.Speed(1f, 100_100),
            Input.Speed(1f, 400_000),
        )
        assertEquals(Phase.LEAVING, h.state.phase)
    }

    @Test
    fun `leaving the lot closes the session`() {
        val h = harness().parkInto(publicLot).feed(
            Input.GearChanged(Gear.DRIVE, 100_000),
            Input.LeftLot(130_000),
        )
        assertEquals(Phase.DRIVING, h.state.phase)
        assertEquals(
            listOf(EventType.PARK, EventType.LEAVING_SOON, EventType.VACATED, EventType.EXIT),
            h.types(),
        )
        assertEquals(Evidence.LEFT_LOT, h.published[2].evidence)
        assertTrue(h.effects.contains(Effect.StopWatchingLot))
        assertNull(h.state.sessionId)
    }

    @Test
    fun `gps jitter while parked does not end the session`() {
        val h = harness().parkInto(publicLot).feed(Input.LeftLot(50_000))
        assertEquals(Phase.PARKED, h.state.phase)
    }

    @Test
    fun `parking again inside the lot starts a new session`() {
        val h = harness().parkInto(publicLot).feed(
            Input.GearChanged(Gear.DRIVE, 100_000),
            Input.Speed(3f, 100_100),
            Input.Speed(3f, 106_000),
            Input.Speed(3f, 111_000),
        )
        assertEquals(Phase.VACATED, h.state.phase)
        val firstSession = h.published.first().sessionId
        h.feed(
            Input.GearChanged(Gear.REVERSE, 150_000),
            Input.GearChanged(Gear.PARK, 155_000),
            Input.Resolved(single(publicLot), null, 155_100),
            Input.Tick(158_100),
            Input.ZoneAnswered("B3-C", 159_000),
        )
        assertEquals(Phase.PARKED, h.state.phase)
        val last = h.published.last()
        assertEquals(EventType.PARK, last.type)
        assertEquals("B3-C", last.zoneId)
        assertFalse(last.sessionId == firstSession)
        assertTrue(EventType.EXIT in h.types())
    }

    @Test
    fun `power loss during the prompt counts as parked`() {
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.Tick(4_100),
            Input.Woke(Gear.PARK, null, 3_600_000),
        )
        assertEquals(Phase.PARKED, h.state.phase)
        assertEquals(listOf(EventType.PARK), h.types())
    }

    @Test
    fun `waking in park with no zone asks where the car is`() {
        val h = harness().parkInto(publicLot, zone = null).feed(Input.Woke(Gear.PARK, null, 3_600_000))
        assertEquals(Phase.PARKED, h.state.phase)
        assertTrue(h.effects.contains(Effect.PromptDeparture(publicLot.id)))
    }

    @Test
    fun `waking in park with a known zone asks nothing`() {
        val h = harness().parkInto(publicLot).feed(Input.Woke(Gear.PARK, null, 3_600_000))
        assertTrue(h.effects.none { it is Effect.PromptDeparture })
    }

    @Test
    fun `a slow boot that missed the departure recovers it`() {
        val h = harness().parkInto(publicLot).feed(Input.Woke(Gear.DRIVE, true, 3_600_000))
        assertEquals(Phase.LEAVING, h.state.phase)
        assertEquals(EventType.LEAVING_SOON, h.published.last().type)
    }

    @Test
    fun `waking already outside the lot closes the session as recovered`() {
        val h = harness().parkInto(publicLot).feed(Input.Woke(Gear.DRIVE, false, 3_600_000))
        assertEquals(Phase.DRIVING, h.state.phase)
        val vacated = h.published.first { it.type == EventType.VACATED }
        assertEquals(Evidence.RECOVERED, vacated.evidence)
        assertEquals(EventType.EXIT, h.published.last().type)
    }

    @Test
    fun `a door opening right after parking is the driver leaving, not a signal`() {
        val h = harness().parkInto(publicLot).feed(
            Input.DriverDoor(true, 6_000),
            Input.DriverSeat(false, 7_000),
        )
        assertEquals(listOf(EventType.PARK), h.types())
    }

    @Test
    fun `after a new power cycle the driver returning sends one weak signal`() {
        val h = harness().parkInto(publicLot).feed(
            Input.Woke(Gear.PARK, null, 3_600_000),
            Input.DriverSeat(true, 3_601_000),
            Input.DriverDoor(true, 3_602_000),
        )
        val signals = h.published.filter { it.type == EventType.LEAVING_SOON }
        assertEquals(1, signals.size)
        assertTrue(signals.single().weak)
        assertEquals(Phase.PARKED, h.state.phase)
    }

    @Test
    fun `an ambiguous lot waits for the driver and the pick counts as confirmation`() {
        val ambiguous = LotResolution.Ambiguous(listOf(publicLot, askLot))
        val h = harness().feed(
            Input.GearChanged(Gear.REVERSE, 0),
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(ambiguous, null, 1_100),
            Input.Tick(4_100),
        )
        assertEquals(Phase.P_TENTATIVE, h.state.phase)
        assertIs<LotResolution.Ambiguous>(h.effects.filterIsInstance<Effect.PromptPark>().single().resolution)
        assertNull(h.state.lot)

        h.feed(Input.Tick(200_000))
        assertEquals(Phase.P_TENTATIVE, h.state.phase)

        h.feed(Input.LotPicked(publicLot, 201_000), Input.ZoneAnswered("B1-A", 202_000))
        assertEquals(Phase.PARKED, h.state.phase)
        assertEquals(listOf(EventType.PARK), h.types())
    }

    @Test
    fun `a repeated gear value does not restart the hold`() {
        val h = harness().feed(
            Input.GearChanged(Gear.PARK, 1_000),
            Input.Resolved(single(publicLot), null, 1_100),
            Input.GearChanged(Gear.PARK, 9_000),
            Input.Tick(11_000),
        )
        assertEquals(Phase.P_TENTATIVE, h.state.phase)
    }

    @Test
    fun `gear property values follow AAOS`() {
        assertEquals(Gear.PARK, Gear.fromProperty(4))
        assertEquals(Gear.REVERSE, Gear.fromProperty(2))
        assertEquals(Gear.NEUTRAL, Gear.fromProperty(1))
        assertEquals(Gear.DRIVE, Gear.fromProperty(8))
        assertEquals(Gear.UNKNOWN, Gear.fromProperty(0))
    }
}
