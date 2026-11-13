package app.parkrelay.core

/**
 * A car on its way to a lot. FAR: the list of zones is kept up to date in silence. NEAR: within
 * [ApproachConfig.nearMeters] of the lot's edge, the list is told once and the car says which zone
 * it is heading for, so the cars behind it are pointed elsewhere. ENDED: parked, or going elsewhere.
 */
enum class ApproachPhase { FAR, NEAR, ENDED }

/** "I am heading to this zone." [published] is false when the lot may not be shared: it then only guides this car. */
data class Claim(val zoneId: String, val at: Long, val published: Boolean)

data class ApproachConfig(
    /** Distance to the lot's edge (catalog radius) at which the list is told. */
    val nearMeters: Double = 100.0,
    /** The server forgets a claim after this long; the car forgets it at the same moment. */
    val claimTtlMillis: Long = 10 * 60 * 1000L,
    /** True: the recommended zone is claimed without asking and the driver may change it by voice. */
    val autoClaim: Boolean = true,
)

data class ApproachState(
    val lot: Lot,
    /** Random, new for every approach, and unrelated to the parking session that may follow. */
    val approachId: String,
    /** Consent given, the lot may be shared, and the driver left "tell others my zone" on. */
    val mayPublish: Boolean,
    val phase: ApproachPhase = ApproachPhase.FAR,
    val distanceKnown: Boolean = false,
    val board: LotBoard? = null,
    val claim: Claim? = null,
    val listTold: Boolean = false,
    val declined: Boolean = false,
    /**
     * False for a car that has just left this very lot with the destination still set: it must be seen
     * away from the lot before being near it means arriving.
     */
    val armed: Boolean = true,
) {
    val claimedZone: String? get() = claim?.zoneId

    /**
     * Whether single departures are spoken as they happen. Far away they are noise, and on arrival
     * the list comes first, so the car stays quiet until the list has been told. A car with no
     * position cannot know it is far, and keeps hearing them.
     */
    val speaksSignals: Boolean
        get() = when (phase) {
            ApproachPhase.NEAR -> listTold
            ApproachPhase.FAR -> !distanceKnown
            ApproachPhase.ENDED -> false
        }
}

sealed interface ApproachInput {
    val at: Long

    /** Metres from the lot's edge, negative inside it. Null when the car has no recent position. */
    data class Distance(val metersToEdge: Double?, override val at: Long) : ApproachInput
    data class Board(val board: LotBoard, override val at: Long) : ApproachInput

    /** The driver named a zone, by voice or by touch while stopped. */
    data class Pick(val zoneId: String, override val at: Long) : ApproachInput

    /** The driver does not want other cars told. */
    data class Decline(override val at: Long) : ApproachInput

    /** This car's parking was confirmed, in whichever zone or lot. */
    data class Parked(override val at: Long) : ApproachInput

    /** New destination or consent withdrawn. [publish] is false when the backend itself was switched. */
    data class Stopped(val publish: Boolean, override val at: Long) : ApproachInput
}

sealed interface ApproachEffect {
    data object FetchBoard : ApproachEffect
    data class Publish(val event: OutEvent) : ApproachEffect

    /**
     * The list, told once. [spoken] is the list itself; [followUp] says which zone was claimed or
     * suggested and is a separate utterance so neither runs long. [title] and [text] go to the notification.
     */
    data class TellList(
        val spoken: String,
        val followUp: String?,
        val title: String,
        val text: String,
        val claimedZone: String?,
        val suggestedZone: String?,
    ) : ApproachEffect

    /** One sentence about a change: the driver's own choice, or another car parking in the claimed zone. */
    data class Tell(val sentence: String) : ApproachEffect
}

data class ApproachStep(val state: ApproachState, val effects: List<ApproachEffect> = emptyList())

/** Pure, like [SessionReducer]: same input, same output, no I/O. */
class ApproachReducer(
    private val config: ApproachConfig = ApproachConfig(),
    private val newId: () -> String,
) {
    fun start(lot: Lot, mayPublish: Boolean, leavingThisLot: Boolean = false): ApproachState =
        ApproachState(lot = lot, approachId = newId(), mayPublish = mayPublish, armed = !leavingThisLot)

    fun reduce(state: ApproachState, input: ApproachInput): ApproachStep {
        if (state.phase == ApproachPhase.ENDED) return ApproachStep(state)
        return when (input) {
            is ApproachInput.Distance -> onDistance(state, input)
            is ApproachInput.Board -> onBoard(state, input)
            is ApproachInput.Pick -> onPick(state, input)
            is ApproachInput.Decline -> onDecline(state, input.at)
            is ApproachInput.Parked -> end(state, input.at, publish = true)
            is ApproachInput.Stopped -> end(state, input.at, input.publish)
        }
    }

    private fun onDistance(state: ApproachState, input: ApproachInput.Distance): ApproachStep {
        val meters = input.metersToEdge ?: return ApproachStep(state)
        val located = state.copy(distanceKnown = true, armed = state.armed || meters > config.nearMeters)
        if (!state.armed) return ApproachStep(located)
        // NEAR latches: GPS jitter at the entrance must not tell the list twice.
        if (located.phase != ApproachPhase.FAR || meters > config.nearMeters) return ApproachStep(located)
        // The board in hand may be seconds or minutes old, and the list is told only once. So it is told
        // from the board fetched now, when that arrives, never from the one in hand.
        return ApproachStep(located.copy(phase = ApproachPhase.NEAR), listOf(ApproachEffect.FetchBoard))
    }

    private fun onBoard(state: ApproachState, input: ApproachInput.Board): ApproachStep {
        val previous = state.board
        val fresh = forgetExpiredClaim(state.copy(board = input.board), input.at)
        if (fresh.phase != ApproachPhase.NEAR) return ApproachStep(fresh)
        if (!fresh.listTold) return tellList(fresh, input.board, input.at)
        return followBoard(fresh, previous, input.board, input.at)
    }

    private fun forgetExpiredClaim(state: ApproachState, at: Long): ApproachState {
        val claim = state.claim ?: return state
        return if (at - claim.at > config.claimTtlMillis) state.copy(claim = null) else state
    }

    private fun tellList(state: ApproachState, board: LotBoard, at: Long): ApproachStep {
        val spoken = BoardText.spokenList(board, state.claimedZone) ?: return ApproachStep(state)
        val text = BoardText.listText(board, state.claimedZone) ?: return ApproachStep(state)
        val told = state.copy(listTold = true)
        val best = BoardText.recommend(board, told.claimedZone)?.zoneId
        val claims = best != null && told.claim == null && config.autoClaim && !told.declined
        val step = if (claims) claim(told, best!!, at) else ApproachStep(told)
        val place = Guidance.spokenZone(best)
        val followUp = when {
            place == null -> null
            step.state.claim?.published == true -> "${place}으로 간다고 알렸어요. 다른 구역이면 말씀해 주세요."
            else -> "${place} 쪽을 권해요."
        }
        val list = ApproachEffect.TellList(
            spoken = "${state.lot.name} ${BoardText.LIST_TITLE}이에요. $spoken",
            followUp = followUp,
            title = "${BoardText.LIST_TITLE} · ${state.lot.name}",
            // The claim just made belongs in the notification too.
            text = BoardText.listText(board, step.state.claimedZone) ?: text,
            claimedZone = step.state.claimedZone,
            suggestedZone = best,
        )
        return ApproachStep(step.state, listOf(list) + step.effects)
    }

    /** After the list was told: keep the claim pointing at a zone that still has a departure. */
    private fun followBoard(state: ApproachState, previous: LotBoard?, board: LotBoard, at: Long): ApproachStep {
        val claim = state.claim
        if (claim == null) {
            // Nothing could be claimed when the list was told; a departure since then can be.
            val best = BoardText.recommend(board, null)?.zoneId
            if (best == null || !config.autoClaim || state.declined) return ApproachStep(state)
            val step = claim(state, best, at)
            val told = if (step.state.claim?.published == true) {
                listOf(ApproachEffect.Tell("${Guidance.spokenZone(best)}으로 간다고 알렸어요."))
            } else emptyList()
            return ApproachStep(step.state, step.effects + told)
        }
        val row = board.zones.firstOrNull { it.zoneId == claim.zoneId }
        if (row != null && row.vacancyAges.isNotEmpty()) return ApproachStep(state)

        val before = previous?.zones?.firstOrNull { it.zoneId == claim.zoneId }
        // A departure that vanished while the parked count rose was taken by a car; otherwise it only aged out.
        val takenByCar = before != null && before.vacancyAges.isNotEmpty() &&
            (row?.parkedAppCars ?: 0) > before.parkedAppCars
        val next = BoardText.recommend(board, null)?.zoneId
        val moved = if (next != null) claim(state.copy(claim = null), next, at, replaces = claim) else release(state, at)
        if (!takenByCar) return moved
        val place = Guidance.spokenZone(claim.zoneId)
        val alternative = next?.let { zoneId ->
            val count = BoardText.unclaimed(board.zones.first { it.zoneId == zoneId }, null)
            " ${Guidance.spokenZone(zoneId)}에 ${count}곳 있어요."
        }.orEmpty()
        val sentence = "가시려던 ${place}에 다른 차가 먼저 주차했어요.$alternative"
        return ApproachStep(moved.state, listOf(ApproachEffect.Tell(sentence)) + moved.effects)
    }

    private fun onPick(state: ApproachState, input: ApproachInput.Pick): ApproachStep {
        if (state.claimedZone == input.zoneId) return ApproachStep(state)
        val step = claim(state.copy(declined = false), input.zoneId, input.at, replaces = state.claim)
        val place = Guidance.spokenZone(input.zoneId)
        val sentence = if (step.state.claim?.published == true) "${place}으로 간다고 알렸어요." else "${place}으로 기억해 둘게요."
        return ApproachStep(step.state, step.effects + ApproachEffect.Tell(sentence))
    }

    private fun onDecline(state: ApproachState, at: Long): ApproachStep {
        val released = release(state, at)
        val effects = if (state.claim?.published == true) {
            released.effects + ApproachEffect.Tell("가는 구역 알림을 취소했어요.")
        } else released.effects
        return ApproachStep(released.state.copy(declined = true), effects)
    }

    private fun end(state: ApproachState, at: Long, publish: Boolean): ApproachStep {
        val released = if (publish) release(state, at) else ApproachStep(state.copy(claim = null))
        return ApproachStep(released.state.copy(phase = ApproachPhase.ENDED), released.effects)
    }

    /** A new CLAIM from the same approach moves the old one on the server, so no cancel is needed first. */
    private fun claim(state: ApproachState, zoneId: String, at: Long, replaces: Claim? = null): ApproachStep {
        val publishes = state.mayPublish
        val time = if (replaces != null) maxOf(at, replaces.at + 1) else at
        val claimed = state.copy(claim = Claim(zoneId, time, publishes))
        val effects = when {
            publishes -> listOf(ApproachEffect.Publish(event(state, EventType.CLAIM, zoneId, time)))
            replaces?.published == true -> listOf(ApproachEffect.Publish(event(state, EventType.CLAIM_CANCEL, replaces.zoneId, time)))
            else -> emptyList()
        }
        return ApproachStep(claimed, effects)
    }

    private fun release(state: ApproachState, at: Long): ApproachStep {
        val claim = state.claim ?: return ApproachStep(state)
        val cleared = state.copy(claim = null)
        if (!claim.published) return ApproachStep(cleared)
        // Strictly later than the claim, so the server never orders the cancel first.
        val time = maxOf(at, claim.at + 1)
        return ApproachStep(cleared, listOf(ApproachEffect.Publish(event(state, EventType.CLAIM_CANCEL, claim.zoneId, time))))
    }

    private fun event(state: ApproachState, type: EventType, zoneId: String, at: Long) = OutEvent(
        eventId = newId(),
        sessionId = state.approachId,
        type = type,
        lotId = state.lot.id,
        zoneId = zoneId,
        occurredAt = at,
    )
}
