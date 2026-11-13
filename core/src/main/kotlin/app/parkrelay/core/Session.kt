package app.parkrelay.core

/** Values follow AAOS VehicleGear: the app reads GEAR_SELECTION and maps it here. */
enum class Gear {
    UNKNOWN, NEUTRAL, REVERSE, PARK, DRIVE;

    companion object {
        fun fromProperty(value: Int): Gear = when (value) {
            1 -> NEUTRAL
            2 -> REVERSE
            4 -> PARK
            8 -> DRIVE
            else -> UNKNOWN
        }
    }
}

enum class Phase { DRIVING, P_TENTATIVE, PARKED, LEAVING, VACATED }

/**
 * CLAIM and CLAIM_CANCEL come from a car approaching a lot, not from a parking session: "I am heading
 * to this zone", so the cars behind it are pointed elsewhere.
 */
enum class EventType { PARK, LEAVING_SOON, LEAVING_CANCEL, VACATED, EXIT, CLAIM, CLAIM_CANCEL }

/** What proved the car left its space, so the board can judge how far to trust a signal. */
enum class Evidence { SPEED, GEAR_TIME, LEFT_LOT, RECOVERED }

/** Sent to the server. Holds no vehicle identity: the session id is random and lasts one parking. */
data class OutEvent(
    val eventId: String,
    val sessionId: String,
    val type: EventType,
    val lotId: String,
    val zoneId: String?,
    val occurredAt: Long,
    val searchSeconds: Long? = null,
    val evidence: Evidence? = null,
    val weak: Boolean = false,
)

/** Everything the reducer asks the outside world to do. It performs no I/O itself. */
sealed interface Effect {
    data class Publish(val event: OutEvent) : Effect
    data object ResolveLot : Effect
    data class WakeAt(val atMillis: Long) : Effect
    data class PromptPark(val resolution: LotResolution, val askShare: Boolean) : Effect
    data object CancelPrompt : Effect
    data class PromptDeparture(val lotId: String) : Effect
    data class SaveCarLocation(val lotId: String, val zoneId: String?, val atMillis: Long) : Effect
    data class WatchLot(val lotId: String) : Effect
    data object StopWatchingLot : Effect
    data class RememberShare(val lotId: String, val allow: Boolean) : Effect
    data class RememberLot(val lotId: String) : Effect
}

sealed interface Input {
    val at: Long

    data class GearChanged(val gear: Gear, override val at: Long) : Input
    data class Speed(val metersPerSecond: Float, override val at: Long) : Input
    data class DriverDoor(val open: Boolean, override val at: Long) : Input
    data class PassengerDoor(val open: Boolean, override val at: Long) : Input
    data class DriverSeat(val occupied: Boolean, override val at: Long) : Input
    data class Resolved(val resolution: LotResolution, val searchSeconds: Long?, override val at: Long) : Input
    data class LotPicked(val lot: Lot, override val at: Long) : Input
    data class ZoneAnswered(val zoneId: String?, override val at: Long) : Input
    data class ShareAnswered(val allow: Boolean, override val at: Long) : Input
    data class LeftLot(override val at: Long) : Input
    data class Tick(override val at: Long) : Input

    /** Service start or screen on. Reconciles what may have happened while the app could not run. */
    data class Woke(val gear: Gear, val insideLot: Boolean?, override val at: Long) : Input
}

data class SessionState(
    val phase: Phase = Phase.DRIVING,
    val gear: Gear = Gear.UNKNOWN,
    val reverseAt: Long? = null,
    val parkSince: Long? = null,
    val resolution: LotResolution? = null,
    val lot: Lot? = null,
    val confidence: Confidence = Confidence.LOW,
    val zoneId: String? = null,
    val sessionId: String? = null,
    val searchSeconds: Long? = null,
    val shareAllowed: Boolean = false,
    val parkPublished: Boolean = false,
    val driverSeatOccupied: Boolean? = null,
    val passengerDoorOpen: Boolean = false,
    val departedAt: Long? = null,
    val movedMeters: Double = 0.0,
    val lastSpeedAt: Long? = null,
    val lastSpeed: Float = 0f,
    val speedSeen: Boolean = false,
    val wokeSinceParked: Boolean = false,
    val weakSignalSent: Boolean = false,
)

data class Step(val state: SessionState, val effects: List<Effect> = emptyList())

/** Initial values from the design doc, section 8. To be tuned with field measurements. */
data class SessionConfig(
    val holdAfterReverseMillis: Long = 3_000,
    val holdWithoutReverseMillis: Long = 10_000,
    val reverseWindowMillis: Long = 90_000,
    val commitAfterMillis: Long = 120_000,
    val cancelWindowMillis: Long = 60_000,
    val confirmWithoutSpeedMillis: Long = 60_000,
    val movedThresholdMeters: Double = 15.0,
    val maxSpeedGapMillis: Long = 5_000,
)

/**
 * The parking session state machine as a pure function: same input, same output, no I/O.
 *
 * Every path that leads to sharing passes through [commit]. A car that only paused in P never
 * reaches [Phase.PARKED], so it can never produce a false "vacated" signal.
 */
class SessionReducer(
    private val config: SessionConfig = SessionConfig(),
    private val newId: () -> String,
    private val consent: () -> Boolean = { true },
    /** The driver's earlier answer for this lot, if any: true to share, false not to. */
    private val sharePreference: (lotId: String) -> Boolean? = { null },
) {
    fun reduce(state: SessionState, input: Input): Step = when (input) {
        // The car service repeats the current value on registration; only a real change counts.
        is Input.GearChanged ->
            if (input.gear == state.gear) Step(state)
            else onGear(state.copy(gear = input.gear), input.gear, input.at)
        is Input.Speed -> onSpeed(state, input)
        is Input.DriverDoor -> onDriverDoor(state, input)
        is Input.PassengerDoor -> Step(state.copy(passengerDoorOpen = input.open))
        is Input.DriverSeat -> onDriverSeat(state, input)
        is Input.Resolved -> onResolved(state, input)
        is Input.LotPicked -> onLotPicked(state, input)
        is Input.ZoneAnswered -> onZone(state, input)
        is Input.ShareAnswered -> onShare(state, input)
        is Input.LeftLot -> onLeftLot(state, input.at)
        is Input.Tick -> onTick(state, input.at)
        is Input.Woke -> onWoke(state, input)
    }

    private fun onGear(state: SessionState, gear: Gear, at: Long): Step {
        val withReverse = if (gear == Gear.REVERSE) state.copy(reverseAt = at) else state
        return when (withReverse.phase) {
            Phase.DRIVING ->
                if (gear == Gear.PARK) beginPark(withReverse, at)
                else Step(withReverse.copy(parkSince = null, resolution = null))

            Phase.P_TENTATIVE ->
                if (gear == Gear.PARK) Step(withReverse)
                // Left P before anything was confirmed: a pause, not a parking. Nothing was sent.
                else Step(clearSession(withReverse), listOf(Effect.CancelPrompt))

            Phase.PARKED ->
                if (gear == Gear.PARK || gear == Gear.UNKNOWN) Step(withReverse)
                else depart(withReverse, at, weak = false)

            Phase.LEAVING ->
                if (gear == Gear.PARK) returnToPark(withReverse, at) else Step(withReverse)

            Phase.VACATED ->
                if (gear == Gear.PARK) {
                    val closed = closeSession(withReverse, at)
                    val parked = beginPark(closed.state, at)
                    Step(parked.state, closed.effects + parked.effects)
                } else Step(withReverse)
        }
    }

    private fun beginPark(state: SessionState, at: Long): Step {
        val hold = holdMillis(state, at)
        return Step(
            state.copy(parkSince = at, resolution = null),
            listOf(Effect.ResolveLot, Effect.WakeAt(at + hold)),
        )
    }

    private fun holdMillis(state: SessionState, at: Long): Long {
        val recentReverse = state.reverseAt?.let { at - it <= config.reverseWindowMillis } == true
        return if (recentReverse) config.holdAfterReverseMillis else config.holdWithoutReverseMillis
    }

    private fun onResolved(state: SessionState, input: Input.Resolved): Step {
        val waiting = state.phase == Phase.DRIVING && state.gear == Gear.PARK && state.parkSince != null
        if (!waiting) return Step(state)
        if (input.resolution == LotResolution.None) {
            // Home, unknown, or too little context: no session, no prompt, nothing sent.
            return Step(state.copy(parkSince = null, resolution = null))
        }
        val withLot = when (val resolved = input.resolution) {
            is LotResolution.Single -> state.copy(lot = resolved.lot, confidence = resolved.confidence)
            else -> state
        }
        val ready = withLot.copy(resolution = input.resolution, searchSeconds = input.searchSeconds)
        return maybeEnterTentative(ready, input.at)
    }

    private fun maybeEnterTentative(state: SessionState, at: Long): Step {
        val since = state.parkSince ?: return Step(state)
        val resolution = state.resolution ?: return Step(state)
        if (at - since < holdMillis(state, since)) return Step(state)
        val lot = state.lot
        val remembered = lot?.let { sharePreference(it.id) }
        val tentative = state.copy(
            phase = Phase.P_TENTATIVE,
            sessionId = newId(),
            shareAllowed = lot != null && allowedToShare(lot, state.confidence, remembered),
        )
        val askShare = lot?.tier == ShareTier.ASK && remembered == null
        return Step(
            tentative,
            listOf(
                Effect.PromptPark(resolution, askShare),
                Effect.WakeAt(since + config.commitAfterMillis),
            ),
        )
    }

    /** A lot picked by position alone may be wrong, so it stays unshared until the driver confirms it. */
    private fun allowedToShare(lot: Lot, confidence: Confidence, remembered: Boolean?): Boolean = when {
        !consent() || lot.tier == ShareTier.NEVER || remembered == false -> false
        remembered == true -> true
        else -> lot.tier == ShareTier.AUTO && confidence == Confidence.HIGH
    }

    private fun onDriverDoor(state: SessionState, input: Input.DriverDoor): Step {
        if (!input.open) return Step(state)
        return when (state.phase) {
            // The driver is getting out, so the hold no longer needs to run its course.
            Phase.DRIVING -> {
                val since = state.parkSince ?: return Step(state)
                val entered = maybeEnterTentative(state.copy(parkSince = since - HOLD_SATISFIED), input.at)
                if (entered.state.phase != Phase.P_TENTATIVE) return Step(state)
                val committed = commit(entered.state.copy(parkSince = since), input.at)
                Step(committed.state, entered.effects + committed.effects)
            }

            Phase.P_TENTATIVE -> commit(state, input.at)
            Phase.PARKED -> weakLeavingSignal(state, input.at)
            else -> Step(state)
        }
    }

    private fun onDriverSeat(state: SessionState, input: Input.DriverSeat): Step {
        val updated = state.copy(driverSeatOccupied = input.occupied)
        return if (input.occupied && updated.phase == Phase.PARKED) weakLeavingSignal(updated, input.at)
        else Step(updated)
    }

    /** Only after a new power cycle: right after parking, a door or seat change means the driver leaving. */
    private fun weakLeavingSignal(state: SessionState, at: Long): Step {
        if (!state.wokeSinceParked || state.weakSignalSent) return Step(state)
        val updated = state.copy(weakSignalSent = true)
        return Step(updated, publishIfShared(updated, EventType.LEAVING_SOON, at, weak = true))
    }

    private fun onLotPicked(state: SessionState, input: Input.LotPicked): Step {
        if (state.phase != Phase.P_TENTATIVE && state.phase != Phase.PARKED) return Step(state)
        val picked = state.copy(
            lot = input.lot,
            confidence = Confidence.HIGH,
            shareAllowed = allowedToShare(input.lot, Confidence.HIGH, sharePreference(input.lot.id)),
        )
        return maybePublishPark(picked, input.at, listOf(Effect.RememberLot(input.lot.id)))
    }

    private fun onZone(state: SessionState, input: Input.ZoneAnswered): Step {
        val lot = state.lot ?: return Step(state)
        return when (state.phase) {
            Phase.P_TENTATIVE, Phase.PARKED -> {
                val saved = listOf(
                    Effect.SaveCarLocation(lot.id, input.zoneId, input.at),
                    Effect.RememberLot(lot.id),
                )
                val confirmed = state.copy(
                    zoneId = input.zoneId,
                    confidence = Confidence.HIGH,
                    shareAllowed = state.shareAllowed || allowedToShare(lot, Confidence.HIGH, sharePreference(lot.id)),
                )
                if (confirmed.phase == Phase.P_TENTATIVE) {
                    val committed = commit(confirmed, input.at)
                    Step(committed.state, saved + committed.effects)
                } else {
                    // A later answer refreshes the zone already on the server for this session.
                    maybePublishPark(confirmed.copy(parkPublished = false), input.at, saved)
                }
            }

            else -> Step(state)
        }
    }

    private fun onShare(state: SessionState, input: Input.ShareAnswered): Step {
        val lot = state.lot ?: return Step(state)
        val remembered = Effect.RememberShare(lot.id, input.allow)
        val updated = state.copy(shareAllowed = input.allow && consent())
        if (!input.allow) return Step(updated, listOf(remembered))
        return maybePublishPark(updated, input.at, listOf(remembered))
    }

    private fun commit(state: SessionState, at: Long): Step {
        val lot = state.lot ?: return Step(state)
        val parked = state.copy(phase = Phase.PARKED, wokeSinceParked = false, weakSignalSent = false)
        val always = listOf(Effect.WatchLot(lot.id), Effect.SaveCarLocation(lot.id, state.zoneId, at))
        return maybePublishPark(parked, at, always)
    }

    private fun maybePublishPark(state: SessionState, at: Long, before: List<Effect>): Step {
        if (state.phase != Phase.PARKED || state.parkPublished || !state.shareAllowed) return Step(state, before)
        val updated = state.copy(parkPublished = true)
        return Step(updated, before + publish(updated, EventType.PARK, at, searchSeconds = updated.searchSeconds))
    }

    private fun depart(state: SessionState, at: Long, weak: Boolean): Step {
        val leaving = state.copy(
            phase = Phase.LEAVING,
            departedAt = at,
            movedMeters = 0.0,
            lastSpeedAt = null,
            lastSpeed = 0f,
        )
        return Step(
            leaving,
            publishIfShared(leaving, EventType.LEAVING_SOON, at, weak = weak) +
                Effect.WakeAt(at + config.confirmWithoutSpeedMillis),
        )
    }

    private fun returnToPark(state: SessionState, at: Long): Step {
        val departed = state.departedAt ?: at
        // With a speed signal the distance is the proof: a car that never moved a car's length has not
        // left, however long it idled. Without one, only a quick return counts as realigning.
        val barelyMoved = state.movedMeters < config.movedThresholdMeters
        val realigning = barelyMoved && (state.speedSeen || at - departed <= config.cancelWindowMillis)
        if (!realigning) return Step(state)
        val parked = state.copy(phase = Phase.PARKED, departedAt = null, movedMeters = 0.0)
        return Step(parked, publishIfShared(parked, EventType.LEAVING_CANCEL, at))
    }

    private fun onSpeed(state: SessionState, input: Input.Speed): Step {
        val seen = state.copy(speedSeen = true)
        if (seen.phase != Phase.LEAVING) return Step(seen.copy(lastSpeedAt = input.at, lastSpeed = input.metersPerSecond))
        val gap = seen.lastSpeedAt?.let { (input.at - it).coerceAtMost(config.maxSpeedGapMillis) } ?: 0L
        val moved = seen.movedMeters + seen.lastSpeed * gap / 1000.0
        val updated = seen.copy(movedMeters = moved, lastSpeedAt = input.at, lastSpeed = input.metersPerSecond)
        return if (moved >= config.movedThresholdMeters) vacate(updated, Evidence.SPEED) else Step(updated)
    }

    private fun vacate(state: SessionState, evidence: Evidence): Step {
        val vacated = state.copy(phase = Phase.VACATED)
        // The space opened when the car left it, not when we became sure of that.
        val openedAt = state.departedAt ?: state.lastSpeedAt ?: 0L
        return Step(vacated, publishIfShared(vacated, EventType.VACATED, openedAt, evidence = evidence))
    }

    private fun onLeftLot(state: SessionState, at: Long): Step = when (state.phase) {
        Phase.LEAVING -> {
            val vacated = vacate(state, Evidence.LEFT_LOT)
            val closed = closeSession(vacated.state, at)
            Step(closed.state, vacated.effects + closed.effects)
        }

        Phase.VACATED -> closeSession(state, at)
        // GPS jitter can place a parked car outside its lot; the gear says it has not moved.
        else -> Step(state)
    }

    private fun closeSession(state: SessionState, at: Long): Step {
        val effects = publishIfShared(state, EventType.EXIT, at) + Effect.StopWatchingLot
        return Step(clearSession(state), effects)
    }

    /**
     * The platform timer runs on uptime and these checks use wall-clock time, so a wake can come a moment
     * early. Nothing else may ever wake these states, so an early wake books another instead of being lost.
     */
    private fun onTick(state: SessionState, at: Long): Step = when (state.phase) {
        Phase.DRIVING -> {
            val due = state.parkSince?.let { it + holdMillis(state, it) }
            if (due != null && at < due) wakeAgain(state, due) else maybeEnterTentative(state, at)
        }

        Phase.P_TENTATIVE -> {
            val due = state.parkSince?.let { it + config.commitAfterMillis }
            when {
                due == null -> Step(state)
                at < due -> wakeAgain(state, due)
                // A driver still sitting in the seat may be waiting for someone; do not call that parked.
                state.driverSeatOccupied != true && state.lot != null -> commit(state, at)
                else -> Step(state)
            }
        }

        Phase.LEAVING -> {
            val due = state.departedAt?.let { it + config.confirmWithoutSpeedMillis }
            when {
                state.speedSeen || due == null -> Step(state)
                at < due -> wakeAgain(state, due)
                else -> vacate(state, Evidence.GEAR_TIME)
            }
        }

        else -> Step(state)
    }

    private fun wakeAgain(state: SessionState, due: Long) = Step(state, listOf(Effect.WakeAt(due)))

    private fun onWoke(state: SessionState, input: Input.Woke): Step {
        val updated = state.copy(gear = input.gear)
        return when (updated.phase) {
            Phase.DRIVING -> if (input.gear == Gear.PARK && updated.parkSince == null) beginPark(updated, input.at) else Step(updated)

            // The power went off during the prompt, which means the car really was parked.
            Phase.P_TENTATIVE -> {
                val committed = commit(updated, input.at)
                if (input.gear == Gear.PARK || input.gear == Gear.UNKNOWN) committed
                else combine(committed) { recoverDeparture(it, input) }
            }

            Phase.PARKED -> {
                val woke = updated.copy(wokeSinceParked = true)
                when (input.gear) {
                    Gear.PARK -> Step(woke, askDepartureZone(woke))
                    Gear.UNKNOWN -> Step(woke)
                    else -> recoverDeparture(woke, input)
                }
            }

            Phase.LEAVING ->
                if (input.gear == Gear.PARK) returnToPark(updated, input.at)
                else if (input.insideLot == false) onLeftLot(updated, input.at)
                else Step(updated)

            Phase.VACATED ->
                if (input.insideLot == false) closeSession(updated, input.at)
                else if (input.gear == Gear.PARK) onGear(updated, Gear.PARK, input.at)
                else Step(updated)
        }
    }

    /** The car moved while the app could not run, so the true departure time is unknown. */
    private fun recoverDeparture(state: SessionState, input: Input.Woke): Step {
        val leaving = depart(state, input.at, weak = false)
        if (input.insideLot != false) return leaving
        val vacated = vacate(leaving.state, Evidence.RECOVERED)
        val closed = closeSession(vacated.state, input.at)
        return Step(closed.state, leaving.effects + vacated.effects + closed.effects)
    }

    private fun askDepartureZone(state: SessionState): List<Effect> {
        val lot = state.lot ?: return emptyList()
        return if (state.zoneId == null) listOf(Effect.PromptDeparture(lot.id)) else emptyList()
    }

    private fun combine(first: Step, next: (SessionState) -> Step): Step {
        val second = next(first.state)
        return Step(second.state, first.effects + second.effects)
    }

    private fun publishIfShared(
        state: SessionState,
        type: EventType,
        at: Long,
        evidence: Evidence? = null,
        weak: Boolean = false,
    ): List<Effect> =
        if (state.shareAllowed && state.parkPublished) publish(state, type, at, evidence = evidence, weak = weak)
        else emptyList()

    private fun publish(
        state: SessionState,
        type: EventType,
        at: Long,
        searchSeconds: Long? = null,
        evidence: Evidence? = null,
        weak: Boolean = false,
    ): List<Effect> = listOf(
        Effect.Publish(
            OutEvent(
                eventId = newId(),
                sessionId = requireNotNull(state.sessionId) { "publishing outside a session" },
                type = type,
                lotId = requireNotNull(state.lot) { "publishing without a lot" }.id,
                zoneId = state.zoneId,
                occurredAt = at,
                searchSeconds = searchSeconds,
                evidence = evidence,
                weak = weak,
            ),
        ),
    )

    private fun clearSession(state: SessionState) = SessionState(
        gear = state.gear,
        reverseAt = state.reverseAt,
        driverSeatOccupied = state.driverSeatOccupied,
        passengerDoorOpen = state.passengerDoorOpen,
        speedSeen = state.speedSeen,
    )

    private companion object {
        // Pushes parkSince far enough back that any hold has already elapsed.
        const val HOLD_SATISFIED = 24 * 60 * 60 * 1000L
    }
}
