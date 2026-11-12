package app.parkrelay.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LotAdvisorTest {
    private val big = Lot("BIG", "큰 공영주차장", 37.5020, 127.0000, 900, ShareTier.AUTO, 150)
    private val small = Lot("SMALL", "작은 공영주차장", 37.5005, 127.0000, 120, ShareTier.AUTO, 100)
    private val office = Lot("OFFICE", "어느타워 주차장", 37.5001, 127.0000, 3000, ShareTier.ASK, 500)
    private val apartment = Lot("APT", "어느아파트", 37.5002, 127.0000, 5000, ShareTier.NEVER, 500)
    private val tiny = Lot("TINY", "아주 작은 공영", 37.5001, 127.0001, 30, ShareTier.AUTO, 100)
    private val far = Lot("FAR", "먼 공영주차장", 37.5200, 127.0000, 2000, ShareTier.AUTO, 300)

    private val advisor = LotAdvisor(LotCatalog(listOf(big, small, office, apartment, tiny, far)))

    private fun at(name: String) = Destination(37.5000, 127.0000, name)

    @Test
    fun `recommends the largest automatically shareable lot within walking distance`() {
        assertEquals("BIG", assertIs<LotAdvice.Nearby>(advisor.advise(at("어느 식당"))).lot.id)
    }

    @Test
    fun `never recommends a lot that may not be open to the public`() {
        val recommended = assertIs<LotAdvice.Nearby>(advisor.advise(at("어느 식당"))).lot
        assertFalse(recommended.id in setOf("OFFICE", "APT"))
    }

    @Test
    fun `when the destination is the lot itself only its size is mentioned`() {
        assertEquals("OFFICE", assertIs<LotAdvice.AtDestination>(advisor.advise(at("어느타워"))).lot.id)
    }

    @Test
    fun `a residential destination is never mentioned`() {
        val advice = advisor.advise(at("어느아파트"))
        assertEquals("BIG", assertIs<LotAdvice.Nearby>(advice).lot.id)
    }

    @Test
    fun `small lots and lots too far away are ignored`() {
        val onlySmall = LotAdvisor(LotCatalog(listOf(tiny, far)))
        assertNull(onlySmall.advise(at("어느 식당")))
    }

    @Test
    fun `no data means silence`() {
        assertNull(advisor.advise(Destination(35.1, 129.0, "부산의 어느 곳")))
    }

    @Test
    fun `capacity is rounded because the data is approximate`() {
        assertEquals(2100, approximateCapacity(2123))
        assertEquals(5400, approximateCapacity(5413))
        assertEquals(920, approximateCapacity(924))
        assertEquals(570, approximateCapacity(569))
        assertEquals(1300, approximateCapacity(1317))
        assertEquals(1000, approximateCapacity(999))
        assertEquals(76, approximateCapacity(76))
    }

    @Test
    fun `the message states size and never promises a space`() {
        val message = LotAdvice.Nearby(big, 220.0).message()
        assertEquals("목적지 근처 큰 공영주차장, 약 900면 규모예요.", message)
        for (promise in listOf("자리", "여유", "비어", "가능")) assertFalse(promise in message, promise)
        assertEquals("어느타워 주차장, 약 3,000면 규모예요.", LotAdvice.AtDestination(office).message())
    }

    @Test
    fun `a destination is announced once, including after routing to the recommendation`() {
        val gate = AdviceGate()
        val first = at("어느 식당")
        val advice = LotAdvice.Nearby(big, 220.0)
        assertTrue(gate.shouldAnnounce(first, advice))
        assertFalse(gate.shouldAnnounce(first, advice))

        val rerouted = Destination(big.lat, big.lng, big.name)
        assertFalse(gate.shouldAnnounce(rerouted, LotAdvice.AtDestination(big)))
    }

    @Test
    fun `a route that outlives a restart is not announced again`() {
        val saved = mutableListOf<String>()
        val before = AdviceGate(onAnnounced = { saved += it })
        assertTrue(before.shouldAnnounce(at("어느 식당"), LotAdvice.Nearby(big, 220.0)))
        assertEquals(listOf(big.id), saved)

        val after = AdviceGate(announcedLotId = saved.last())
        assertFalse(after.shouldAnnounce(at("어느 식당"), LotAdvice.Nearby(big, 220.0)))
    }

    @Test
    fun `a genuinely new destination is announced again`() {
        val gate = AdviceGate()
        assertTrue(gate.shouldAnnounce(at("어느 식당"), LotAdvice.Nearby(big, 220.0)))
        assertTrue(gate.shouldAnnounce(Destination(37.52, 127.0, "다른 곳"), LotAdvice.Nearby(far, 10.0)))
    }

    /** Runs against the real generated catalog, when present, to catch data-driven surprises. */
    @Test
    fun `real catalog examples`() {
        val file = listOf("../app/src/main/assets/lots.tsv", "app/src/main/assets/lots.tsv").map(::File).firstOrNull { it.exists() }
            ?: return
        val real = LotAdvisor(file.useLines { LotCatalog.parse(it) })

        val coex = assertIs<LotAdvice.AtDestination>(real.advise(Destination(37.51243, 127.05885, "코엑스")))
        assertEquals("코엑스 주차장", coex.lot.name)
        assertEquals("코엑스 주차장, 약 2,100면 규모예요.", coex.message())

        val nearTancheon = assertIs<LotAdvice.Nearby>(real.advise(Destination(37.5150, 127.0660, "근처 식당")))
        assertEquals(ShareTier.AUTO, nearTancheon.lot.tier)
        assertTrue(nearTancheon.distanceMeters <= 500.0)
    }
}
