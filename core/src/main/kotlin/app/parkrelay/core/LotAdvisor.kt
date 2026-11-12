package app.parkrelay.core

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** What to tell a driver about parking around their destination, before any other driver has shared. */
sealed interface LotAdvice {
    /** The destination is itself a lot: there is nothing to route to, only its size to mention. */
    data class AtDestination(val lot: Lot) : LotAdvice

    /** A larger known lot near the destination, which the driver may choose to navigate to. */
    data class Nearby(val lot: Lot, val distanceMeters: Double) : LotAdvice
}

/**
 * Turns the capacity of known lots into value on day one: it needs no other users and no server.
 *
 * Capacity is size, not current availability, so wording built on this must never promise a space.
 * Only AUTO lots are recommended. The data does not say who may use a lot, so recommending
 * anything else could send a driver to an apartment complex or a private company garage.
 */
class LotAdvisor(
    private val catalog: LotCatalog,
    private val searchRadiusM: Double = 500.0,
    private val atDestinationRadiusM: Double = 200.0,
    private val minCapacity: Int = 50,
) {
    fun advise(destination: Destination): LotAdvice? {
        val named = catalog.near(destination.lat, destination.lng, atDestinationRadiusM)
            .map { it.first }
            .filter { it.tier != ShareTier.NEVER && destination.nameMatches(it) }
        named.maxByOrNull { it.capacity }?.let { return LotAdvice.AtDestination(it) }

        return catalog.near(destination.lat, destination.lng, searchRadiusM)
            .filter { (lot, _) -> lot.tier == ShareTier.AUTO && lot.capacity >= minCapacity }
            .maxByOrNull { (lot, _) -> lot.capacity }
            ?.let { (lot, distance) -> LotAdvice.Nearby(lot, distance) }
    }
}

/**
 * Remembers what was already said, so a destination is announced once. Routing to the recommended
 * lot changes the destination again, and that must not trigger a second announcement.
 */
class AdviceGate(
    /** Restored after a restart, since a route can outlive the app and must not be announced again. */
    private var announcedLotId: String? = null,
    private val onAnnounced: (lotId: String) -> Unit = {},
) {
    private var announcedDestination: Pair<Double, Double>? = null

    fun shouldAnnounce(destination: Destination, advice: LotAdvice): Boolean {
        val lotId = when (advice) {
            is LotAdvice.AtDestination -> advice.lot.id
            is LotAdvice.Nearby -> advice.lot.id
        }
        val place = destination.lat to destination.lng
        if (place == announcedDestination || lotId == announcedLotId) return false
        announcedDestination = place
        announcedLotId = lotId
        onAnnounced(lotId)
        return true
    }
}

/** Rounds to two significant figures: the data is from 2023 and is not exact. */
fun approximateCapacity(capacity: Int): Int {
    if (capacity < 100) return capacity
    val unit = 10.0.pow(floor(log10(capacity.toDouble())) - 1).toInt()
    return (capacity + unit / 2) / unit * unit
}

/** Spoken and notification text. Says how big a lot is and never that a space is available. */
fun LotAdvice.message(): String = when (this) {
    is LotAdvice.AtDestination -> "${lot.name}, 약 ${"%,d".format(approximateCapacity(lot.capacity))}면 규모예요."
    is LotAdvice.Nearby -> "목적지 근처 ${lot.name}, 약 ${"%,d".format(approximateCapacity(lot.capacity))}면 규모예요."
}
