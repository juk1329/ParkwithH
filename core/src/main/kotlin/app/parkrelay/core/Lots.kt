package app.parkrelay.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Who may receive events from a lot. See the sharing tiers in the design doc, section 6.2. */
enum class ShareTier { AUTO, ASK, NEVER }

data class Lot(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val capacity: Int,
    val tier: ShareTier,
    val radiusM: Int,
)

/** A position with the time it was measured. Stays in the car; only derived values leave. */
data class Fix(val lat: Double, val lng: Double, val atMillis: Long)

fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = phi2 - phi1
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLng / 2).pow(2)
    return 2 * 6_371_000.0 * asin(sqrt(h))
}

/** The in-car lot list with a coarse grid index, so lookups never touch the network. */
class LotCatalog(lots: List<Lot>) {
    private val grid: Map<Pair<Int, Int>, List<Lot>> = lots.groupBy { key(it.lat, it.lng) }
    val size: Int = lots.size

    /** Lots within [meters] of the point, nearest first. */
    fun near(lat: Double, lng: Double, meters: Double): List<Pair<Lot, Double>> {
        val reach = (meters / CELL_METERS).toInt() + 1
        val (row, col) = key(lat, lng)
        val found = mutableListOf<Pair<Lot, Double>>()
        for (r in row - reach..row + reach) {
            for (c in col - reach..col + reach) {
                for (lot in grid[r to c].orEmpty()) {
                    val distance = distanceMeters(lat, lng, lot.lat, lot.lng)
                    if (distance <= meters) found += lot to distance
                }
            }
        }
        return found.sortedBy { it.second }
    }

    fun byId(id: String): Lot? = grid.values.asSequence().flatten().firstOrNull { it.id == id }

    private fun key(lat: Double, lng: Double) =
        Math.floor(lat / CELL_DEGREES).toInt() to Math.floor(lng / CELL_DEGREES).toInt()

    companion object {
        private const val CELL_DEGREES = 0.005
        // One cell spans at least this far in both axes at Korean latitudes, so the search cannot miss.
        private const val CELL_METERS = 430.0

        private const val COLUMN_COUNT = 7

        /** Parses the tab-separated file written by tools/build_lot_catalog.py. */
        fun parse(lines: Sequence<String>): LotCatalog {
            val lots = lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val cells = line.split('\t')
                require(cells.size >= COLUMN_COUNT) { "bad catalog line: $line" }
                Lot(
                    id = cells[0],
                    name = cells[1],
                    lat = cells[2].toDouble(),
                    lng = cells[3].toDouble(),
                    capacity = cells[4].toInt(),
                    tier = ShareTier.valueOf(cells[5]),
                    radiusM = cells[6].toInt(),
                )
            }.toList()
            return LotCatalog(lots)
        }
    }
}

/** How sure we are about the lot. A low-confidence lot is not shared until the driver confirms it. */
enum class Confidence { HIGH, LOW }

sealed interface LotResolution {
    /** No session at all: home, an unknown place, or nothing to go on. Nothing is shown or sent. */
    data object None : LotResolution

    data class Single(val lot: Lot, val confidence: Confidence) : LotResolution

    /** Several plausible lots. The driver picks one, and that pick counts as confirmation. */
    data class Ambiguous(val candidates: List<Lot>) : LotResolution
}

/**
 * Decides which lot the car has just parked in, at the moment it shifts to P.
 *
 * Seoul is too dense for a geofence to identify a lot, so this uses context in a fixed order:
 * the navigation destination, then lots this car used before, then the last known position.
 */
class LotResolver(
    private val catalog: LotCatalog,
    private val destinationRadiusM: Double = 200.0,
    private val destinationSanityM: Double = 500.0,
    private val maxFixAgeMillis: Long = 10 * 60 * 1000L,
    private val maxCandidates: Int = 3,
) {
    fun resolve(
        destination: Destination?,
        confirmedLotIds: Set<String>,
        lastFix: Fix?,
        now: Long,
    ): LotResolution {
        val fix = lastFix?.takeIf { now - it.atMillis <= maxFixAgeMillis }
        fromDestination(destination, fix)?.let { return it }
        if (fix == null) return LotResolution.None

        val covering = catalog.near(fix.lat, fix.lng, MAX_RADIUS_M)
            .filter { (lot, distance) -> distance <= lot.radiusM }
        if (covering.isEmpty()) return LotResolution.None

        covering.firstOrNull { (lot, _) -> lot.id in confirmedLotIds && lot.tier != ShareTier.NEVER }
            ?.let { (lot, _) -> return LotResolution.Single(lot, Confidence.HIGH) }

        // The nearest lot is residential: assume the car is at home, even with a public lot next door.
        if (covering.first().first.tier == ShareTier.NEVER) return LotResolution.None

        val candidates = covering.map { it.first }.filter { it.tier != ShareTier.NEVER }
        return when (candidates.size) {
            1 -> LotResolution.Single(candidates.single(), Confidence.LOW)
            else -> LotResolution.Ambiguous(candidates.take(maxCandidates))
        }
    }

    private fun fromDestination(destination: Destination?, fix: Fix?): LotResolution? {
        destination ?: return null
        // The car may have stopped somewhere else entirely; only trust the destination if it is nearby.
        if (fix != null && distanceMeters(fix.lat, fix.lng, destination.lat, destination.lng) > destinationSanityM) {
            return null
        }
        val confidence = if (fix != null) Confidence.HIGH else Confidence.LOW
        val nearby = catalog.near(destination.lat, destination.lng, destinationRadiusM)
            .map { it.first }
            .filter { it.tier != ShareTier.NEVER }
        return when {
            nearby.isEmpty() -> null
            nearby.size == 1 -> LotResolution.Single(nearby.single(), confidence)
            else -> {
                val named = nearby.filter { destination.nameMatches(it) }
                if (named.size == 1) LotResolution.Single(named.single(), confidence)
                else LotResolution.Ambiguous(nearby.take(maxCandidates))
            }
        }
    }

    private companion object {
        // Largest radius written by the catalog builder.
        const val MAX_RADIUS_M = 500.0
    }
}

data class Destination(val lat: Double, val lng: Double, val name: String) {
    fun nameMatches(lot: Lot): Boolean {
        val wanted = normalize(name)
        val listed = normalize(lot.name)
        return wanted.isNotEmpty() && listed.isNotEmpty() && (wanted in listed || listed in wanted)
    }

    private fun normalize(text: String) = text.replace(" ", "").replace("주차장", "")
}
