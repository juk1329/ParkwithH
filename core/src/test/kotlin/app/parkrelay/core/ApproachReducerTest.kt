package app.parkrelay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApproachReducerTest {
    private val lot = Lot("L1", "코엑스 주차장", 37.51, 127.05, 2123, ShareTier.AUTO, 300)

    private fun counter(): () -> String {
        var n = 0
        return { "id-${++n}" }
    }

    private class Harness(private val reducer: ApproachReducer, lot: Lot, mayPublish: Boolean) {
        var state = reducer.start(lot, mayPublish)
        val effects = mutableListOf<ApproachEffect>()

        fun feed(vararg inputs: ApproachInput): Harness {
            for (input in inputs) {
                val step = reducer.reduce(state, input)
                state = step.state
                effects += step.effects
            }
            return this
        }

        /** Comes within range, and the board fetched at that moment arrives. */
        fun arrive(board: LotBoard): Harness =
            feed(ApproachInput.Distance(50.0, 1_000), ApproachInput.Board(board, 1_000))

                val published get() = effects.filterIsInstance<ApproachEffect.Publish>().map { it.event }
        val lists get() = effects.filterIsInstance<ApproachEffect.TellList>()
        val told get() = effects.filterIsInstance<ApproachEffect.Tell>().map { it.sentence }
    }

    private fun harness(mayPublish: Boolean = true, config: ApproachConfig = ApproachConfig()) =
        Harness(ApproachReducer(config, counter()), lot, mayPublish)

    private fun row(zone: String, ages: List<Long> = emptyList(), leaving: Int = 0, parked: Int = 0, claims: Int = 0) =
        ZoneRow(zone, ages, leaving, parked, claims)

    private fun board(vararg rows: ZoneRow) = LotBoard(2123, 3, rows.toList())

    private val twoZones = board(row("B1-A", listOf(240_000)), row("B2-B", listOf(30_000, 90_000)))

    @Test
    fun `far from the lot the list is kept but nothing is said`() {
        val h = harness().feed(
            ApproachInput.Distance(900.0, 0),
            ApproachInput.Board(twoZones, 1_000),
        )
        assertEquals(ApproachPhase.FAR, h.state.phase)
        assertTrue(h.effects.isEmpty())
        assertFalse(h.state.speaksSignals)
    }

    @Test
    fun `a car with no position keeps hearing single departures`() {
        val h = harness().feed(ApproachInput.Distance(null, 0), ApproachInput.Board(twoZones, 1_000))
        assertEquals(ApproachPhase.FAR, h.state.phase)
        assertTrue(h.state.speaksSignals)
        assertTrue(h.lists.isEmpty())
    }

    @Test
    fun `a hundred metres out the list is told once and the best zone is claimed`() {
        val h = harness().feed(
            ApproachInput.Board(twoZones, 0),
            ApproachInput.Distance(250.0, 1_000),
            ApproachInput.Distance(100.0, 2_000),
        )
        // The board in hand is not trusted for the one telling: a fresh one is asked for first.
        assertEquals(listOf<ApproachEffect>(ApproachEffect.FetchBoard), h.effects)
        h.feed(ApproachInput.Board(twoZones, 2_100))
        assertEquals(ApproachPhase.NEAR, h.state.phase)
        val list = h.lists.single()
        assertEquals("코엑스 주차장 주차 가능 구역이에요. 지하 2층 B구역 2곳, 지하 1층 A구역 1곳.", list.spoken)
        assertEquals("지하 2층 B구역으로 간다고 알렸어요. 다른 구역이면 말씀해 주세요.", list.followUp)
        assertEquals("주차 가능 구역 · 코엑스 주차장", list.title)
        assertEquals("지하 1층  A구역 1곳 · 4분 전\n지하 2층  B구역 2곳 · 내가 가는 중", list.text)
        assertEquals("B2-B", list.claimedZone)
        val claim = h.published.single()
        assertEquals(EventType.CLAIM, claim.type)
        assertEquals("B2-B", claim.zoneId)
        assertEquals("L1", claim.lotId)
        assertEquals(h.state.approachId, claim.sessionId)
        assertTrue(ApproachEffect.FetchBoard in h.effects)
    }

    @Test
    fun `on arrival the list comes before any single departure`() {
        val h = harness().feed(ApproachInput.Distance(900.0, 0), ApproachInput.Distance(60.0, 1_000))
        // Near, but the fresh board is still on its way: a departure heard now would talk over the list.
        assertFalse(h.state.speaksSignals)
        h.feed(ApproachInput.Board(twoZones, 1_300))
        assertTrue(h.state.speaksSignals)
    }

    @Test
    fun `near a lot with no news the first departure is told as the list, not twice`() {
        val h = harness().arrive(board(row("B1-A", parked = 2)))
        assertFalse(h.state.speaksSignals)
        h.feed(ApproachInput.Board(board(row("B1-A", listOf(1_000), parked = 1)), 9_000))
        assertEquals(1, h.lists.size)
        assertTrue(h.state.speaksSignals)
    }

    @Test
    fun `jitter at the entrance does not tell the list twice`() {
        val h = harness().feed(
            ApproachInput.Board(twoZones, 0),
            ApproachInput.Distance(90.0, 1_000),
            ApproachInput.Distance(140.0, 2_000),
            ApproachInput.Distance(60.0, 3_000),
            ApproachInput.Board(twoZones, 4_000),
        )
        assertEquals(1, h.lists.size)
        assertEquals(1, h.published.size)
    }

    @Test
    fun `arriving before the board has loaded tells the list when it comes`() {
        val h = harness().feed(ApproachInput.Distance(40.0, 0))
        assertEquals(listOf<ApproachEffect>(ApproachEffect.FetchBoard), h.effects)
        h.feed(ApproachInput.Board(twoZones, 500))
        assertEquals(1, h.lists.size)
        assertEquals("B2-B", h.state.claimedZone)
    }

    @Test
    fun `no news says nothing, and the first departure after that is the list`() {
        val quiet = board(row("B1-A", parked = 2))
        val h = harness().feed(ApproachInput.Distance(40.0, 0), ApproachInput.Board(quiet, 500))
        assertTrue(h.lists.isEmpty())
        assertNull(h.state.claim)
        h.feed(ApproachInput.Board(board(row("B1-A", listOf(2_000), parked = 1)), 9_000))
        assertEquals("B1-A", h.lists.single().claimedZone)
    }

    @Test
    fun `a zone another car is heading for is told but not claimed`() {
        val ahead = board(row("B1-A", listOf(20_000), claims = 1), row("B2-B", listOf(200_000)))
        val h = harness().arrive(ahead)
        assertEquals("B2-B", h.state.claimedZone)
        assertEquals(
            "코엑스 주차장 주차 가능 구역이에요. 지하 2층 B구역 1곳. 지하 1층 A구역은 앞차가 가는 중이에요.",
            h.lists.single().spoken,
        )
    }

    @Test
    fun `when every departure has a car on its way nothing is claimed`() {
        val taken = board(row("B1-A", listOf(20_000), claims = 1))
        val h = harness().arrive(taken)
        assertNull(h.state.claim)
        assertNull(h.lists.single().followUp)
        assertTrue(h.published.isEmpty())
    }

    @Test
    fun `a lot that may not be shared gets the list and a suggestion but sends nothing`() {
        val h = harness(mayPublish = false).arrive(twoZones)
        assertTrue(h.published.isEmpty())
        assertEquals("지하 2층 B구역 쪽을 권해요.", h.lists.single().followUp)
        // The suggestion still guides this car's own parking prompt.
        assertEquals("B2-B", h.state.claimedZone)
    }

    @Test
    fun `with asking switched on nothing is claimed until the driver picks`() {
        val h = harness(config = ApproachConfig(autoClaim = false))
            .arrive(twoZones)
        assertTrue(h.published.isEmpty())
        assertEquals("B2-B", h.lists.single().suggestedZone)
        h.feed(ApproachInput.Pick("B2-B", 5_000))
        assertEquals(EventType.CLAIM, h.published.single().type)
    }

    @Test
    fun `naming another zone moves the claim`() {
        val h = harness().arrive(twoZones).feed(
            ApproachInput.Pick("B1-A", 6_000),
        )
        assertEquals(listOf(EventType.CLAIM, EventType.CLAIM), h.published.map { it.type })
        assertEquals(listOf("B2-B", "B1-A"), h.published.map { it.zoneId })
        assertEquals("B1-A", h.state.claimedZone)
        assertEquals(listOf("지하 1층 A구역으로 간다고 알렸어요."), h.told)
    }

    @Test
    fun `declining cancels the claim and stops further claims`() {
        val h = harness().arrive(twoZones).feed(
            ApproachInput.Decline(6_000),
            ApproachInput.Board(twoZones, 9_000),
        )
        assertEquals(listOf(EventType.CLAIM, EventType.CLAIM_CANCEL), h.published.map { it.type })
        assertNull(h.state.claim)
        assertEquals(listOf("가는 구역 알림을 취소했어요."), h.told)
    }

    @Test
    fun `parking releases the claim exactly once`() {
        val h = harness().arrive(twoZones).feed(
            ApproachInput.Parked(60_000),
            ApproachInput.Parked(61_000),
            ApproachInput.Board(twoZones, 62_000),
        )
        assertEquals(listOf(EventType.CLAIM, EventType.CLAIM_CANCEL), h.published.map { it.type })
        assertEquals(ApproachPhase.ENDED, h.state.phase)
        val (claim, cancel) = h.published
        assertTrue(cancel.occurredAt > claim.occurredAt)
        assertEquals(claim.sessionId, cancel.sessionId)
    }

    @Test
    fun `a cancel in the same millisecond is still ordered after its claim`() {
        val h = harness().arrive(twoZones).feed(
            ApproachInput.Parked(1_000),
        )
        val (claim, cancel) = h.published
        assertEquals(claim.occurredAt + 1, cancel.occurredAt)
    }

    @Test
    fun `switching backends forgets the claim without sending`() {
        val h = harness().arrive(twoZones).feed(
            ApproachInput.Stopped(publish = false, at = 5_000),
        )
        assertEquals(listOf(EventType.CLAIM), h.published.map { it.type })
        assertEquals(ApproachPhase.ENDED, h.state.phase)
        assertNull(h.state.claim)
    }

    @Test
    fun `another car parking in my zone is told once with the next best zone`() {
        val after = board(row("B1-A", listOf(260_000)), row("B2-B", parked = 1))
        val h = harness().feed(
            ApproachInput.Distance(50.0, 1_000),
            ApproachInput.Board(board(row("B1-A", listOf(240_000)), row("B2-B", listOf(30_000))), 1_000),
        )
        assertEquals("B2-B", h.state.claimedZone)
        h.feed(ApproachInput.Board(after, 20_000), ApproachInput.Board(after, 35_000))
        assertEquals(
            listOf("가시려던 지하 2층 B구역에 다른 차가 먼저 주차했어요. 지하 1층 A구역에 1곳 있어요."),
            h.told,
        )
        assertEquals("B1-A", h.state.claimedZone)
        assertEquals(listOf("B2-B", "B1-A"), h.published.map { it.zoneId })
        assertTrue(h.published.all { it.type == EventType.CLAIM })
    }

    @Test
    fun `a departure that only aged out moves the claim without a word`() {
        val h = harness().feed(
            ApproachInput.Distance(50.0, 1_000),
            ApproachInput.Board(board(row("B1-A", listOf(240_000)), row("B2-B", listOf(590_000))), 1_000),
        )
        // B1-A is fresher but both have one; the tie goes to the freshest.
        assertEquals("B1-A", h.state.claimedZone)
        h.feed(ApproachInput.Pick("B2-B", 2_000))
        h.feed(ApproachInput.Board(board(row("B1-A", listOf(260_000)), row("B2-B")), 20_000))
        assertEquals("B1-A", h.state.claimedZone)
        assertEquals(listOf("지하 2층 B구역으로 간다고 알렸어요."), h.told)
    }

    @Test
    fun `with nowhere else to go a taken zone just releases the claim`() {
        val h = harness().feed(
            ApproachInput.Distance(50.0, 1_000),
            ApproachInput.Board(board(row("B2-B", listOf(30_000))), 1_000),
            ApproachInput.Board(board(row("B2-B", parked = 1)), 20_000),
        )
        assertNull(h.state.claim)
        assertEquals(listOf(EventType.CLAIM, EventType.CLAIM_CANCEL), h.published.map { it.type })
        assertEquals(listOf("가시려던 지하 2층 B구역에 다른 차가 먼저 주차했어요."), h.told)
    }

    @Test
    fun `a claim older than the server keeps it is forgotten and made again`() {
        val h = harness(config = ApproachConfig(claimTtlMillis = 60_000)).arrive(twoZones).feed(
            ApproachInput.Board(twoZones, 100_000),
        )
        assertEquals(listOf(EventType.CLAIM, EventType.CLAIM), h.published.map { it.type })
        assertIs<Claim>(h.state.claim)
        assertEquals(100_000, h.state.claim?.at)
    }

    @Test
    fun `a departure after an empty list is claimed and said`() {
        val soon = board(row("B2-C", leaving = 1))
        val h = harness().arrive(soon)
        assertEquals("코엑스 주차장 주차 가능 구역이에요. 지하 2층 C구역에서 곧 자리가 날 것 같아요.", h.lists.single().spoken)
        assertNull(h.state.claim)
        h.feed(ApproachInput.Board(board(row("B2-C", listOf(1_000))), 8_000))
        assertEquals("B2-C", h.state.claimedZone)
        assertEquals(listOf("지하 2층 C구역으로 간다고 알렸어요."), h.told)
    }

    @Test
    fun `a car leaving this lot is not told the list on its way out`() {
        val reducer = ApproachReducer(newId = counter())
        var state = reducer.start(lot, mayPublish = true, leavingThisLot = true)
        val effects = mutableListOf<ApproachEffect>()
        fun feed(input: ApproachInput) {
            val step = reducer.reduce(state, input)
            state = step.state
            effects += step.effects
        }
        feed(ApproachInput.Board(twoZones, 0))
        feed(ApproachInput.Distance(-120.0, 1_000))
        feed(ApproachInput.Distance(60.0, 2_000))
        assertEquals(ApproachPhase.FAR, state.phase)
        assertTrue(effects.isEmpty())
        feed(ApproachInput.Board(twoZones, 3_000))
        assertTrue(effects.isEmpty())
        // Once it has been away, coming back is an arrival like any other.
        feed(ApproachInput.Distance(800.0, 60_000))
        feed(ApproachInput.Distance(90.0, 120_000))
        feed(ApproachInput.Board(twoZones, 120_100))
        assertEquals(ApproachPhase.NEAR, state.phase)
        assertEquals(1, effects.filterIsInstance<ApproachEffect.TellList>().size)
    }
}
