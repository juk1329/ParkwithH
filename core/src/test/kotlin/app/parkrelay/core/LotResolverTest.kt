package app.parkrelay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LotResolverTest {
    private val now = 1_000_000_000L

    // Offsets of 0.001 degrees are roughly 110 m north-south.
    private val publicLot = Lot("PUB", "테스트 공영주차장", 37.5000, 127.0000, 500, ShareTier.AUTO, 150)
    private val neighbour = Lot("ASK", "테스트타워 주차장", 37.5005, 127.0000, 400, ShareTier.ASK, 150)
    private val apartment = Lot("APT", "테스트아파트", 37.5100, 127.0000, 800, ShareTier.NEVER, 150)
    private val publicNearHome = Lot("PUB2", "옆 공영주차장", 37.5110, 127.0000, 300, ShareTier.AUTO, 150)
    private val lone = Lot("LONE", "외딴 공영주차장", 37.6000, 127.1000, 200, ShareTier.AUTO, 100)

    private val resolver = LotResolver(LotCatalog(listOf(publicLot, neighbour, apartment, publicNearHome, lone)))

    private fun fix(lat: Double, lng: Double, ageMillis: Long = 0) = Fix(lat, lng, now - ageMillis)

    @Test
    fun `destination at a lot gives high confidence`() {
        val result = resolver.resolve(
            Destination(37.6000, 127.1000, "외딴 공영주차장"), emptySet(), fix(37.6001, 127.1000), now,
        )
        assertEquals(LotResolution.Single(lone, Confidence.HIGH), result)
    }

    @Test
    fun `destination name breaks a tie between neighbouring lots`() {
        val result = resolver.resolve(
            Destination(37.5002, 127.0000, "테스트타워"), emptySet(), fix(37.5002, 127.0000), now,
        )
        assertEquals(LotResolution.Single(neighbour, Confidence.HIGH), result)
    }

    @Test
    fun `neighbouring lots with no name match are ambiguous`() {
        val result = resolver.resolve(
            Destination(37.5002, 127.0000, "근처 식당"), emptySet(), fix(37.5002, 127.0000), now,
        )
        assertEquals(setOf(publicLot, neighbour), assertIs<LotResolution.Ambiguous>(result).candidates.toSet())
    }

    @Test
    fun `a destination far from the car is ignored`() {
        val result = resolver.resolve(
            Destination(37.6000, 127.1000, "외딴 공영주차장"), emptySet(), fix(37.5000, 127.0000), now,
        )
        assertIs<LotResolution.Ambiguous>(result)
    }

    @Test
    fun `destination without a fresh fix needs confirmation`() {
        val result = resolver.resolve(Destination(37.6000, 127.1000, "외딴"), emptySet(), null, now)
        assertEquals(LotResolution.Single(lone, Confidence.LOW), result)
    }

    @Test
    fun `a lot this car used before is trusted`() {
        val result = resolver.resolve(null, setOf("ASK"), fix(37.5002, 127.0000), now)
        assertEquals(LotResolution.Single(neighbour, Confidence.HIGH), result)
    }

    @Test
    fun `a single lot from position alone needs confirmation`() {
        val result = resolver.resolve(null, emptySet(), fix(37.6000, 127.1000), now)
        assertEquals(LotResolution.Single(lone, Confidence.LOW), result)
    }

    @Test
    fun `several lots from position alone are ambiguous`() {
        assertIs<LotResolution.Ambiguous>(resolver.resolve(null, emptySet(), fix(37.5002, 127.0000), now))
    }

    @Test
    fun `at home nothing happens even with a public lot next door`() {
        assertEquals(LotResolution.None, resolver.resolve(null, emptySet(), fix(37.5100, 127.0000), now))
    }

    @Test
    fun `a residential lot is never offered as a candidate`() {
        val result = resolver.resolve(
            Destination(37.5105, 127.0000, "옆 공영주차장"), emptySet(), fix(37.5105, 127.0000), now,
        )
        assertEquals(LotResolution.Single(publicNearHome, Confidence.HIGH), result)
    }

    @Test
    fun `a stale fix is not used`() {
        val result = resolver.resolve(null, emptySet(), fix(37.6000, 127.1000, ageMillis = 11 * 60 * 1000L), now)
        assertEquals(LotResolution.None, result)
    }

    @Test
    fun `no context and no lot nearby give no session`() {
        assertEquals(LotResolution.None, resolver.resolve(null, emptySet(), null, now))
        assertEquals(LotResolution.None, resolver.resolve(null, emptySet(), fix(35.0, 129.0), now))
    }

    @Test
    fun `grid lookup finds lots across cell boundaries and sorts by distance`() {
        val a = Lot("A", "a", 37.49999, 127.00001, 100, ShareTier.AUTO, 100)
        val b = Lot("B", "b", 37.50001, 126.99999, 100, ShareTier.AUTO, 100)
        val far = Lot("F", "f", 37.5040, 127.0000, 100, ShareTier.AUTO, 100)
        val catalog = LotCatalog(listOf(far, b, a))
        val near = catalog.near(37.49999, 127.00001, 100.0).map { it.first.id }
        assertEquals(listOf("A", "B"), near)
        assertEquals(3, catalog.near(37.5, 127.0, 500.0).size)
    }

    @Test
    fun `parses the catalog file format`() {
        val catalog = LotCatalog.parse(
            sequenceOf(
                "id\tname\tlat\tlng\tcapacity\ttier\tradius_m\talt_ids",
                "X\t이름\t37.500000\t127.000000\t120\tASK\t100\tY,Z",
                "",
            ),
        )
        assertEquals(1, catalog.size)
        assertEquals(ShareTier.ASK, catalog.byId("X")?.tier)
        assertEquals(120, catalog.byId("X")?.capacity)
    }
}
