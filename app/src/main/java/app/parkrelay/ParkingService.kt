package app.parkrelay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.parkrelay.core.AdviceGate
import app.parkrelay.core.ApproachEffect
import app.parkrelay.core.ApproachInput
import app.parkrelay.core.ApproachPhase
import app.parkrelay.core.ApproachReducer
import app.parkrelay.core.ApproachState
import app.parkrelay.core.Confidence
import app.parkrelay.core.Destination
import app.parkrelay.core.Effect
import app.parkrelay.core.EventType
import app.parkrelay.core.Fix
import app.parkrelay.core.FixHistory
import app.parkrelay.core.Gear
import app.parkrelay.core.Guidance
import app.parkrelay.core.Input
import app.parkrelay.core.Lot
import app.parkrelay.core.LotAdvice
import app.parkrelay.core.LotAdvisor
import app.parkrelay.core.LotBoard
import app.parkrelay.core.LotCatalog
import app.parkrelay.core.LotResolution
import app.parkrelay.core.LotResolver
import app.parkrelay.core.Phase
import app.parkrelay.core.SessionReducer
import app.parkrelay.core.SessionState
import app.parkrelay.core.ShareTier
import app.parkrelay.core.SpeechGate
import app.parkrelay.core.SpokenAnswer
import app.parkrelay.core.ZoneParser
import app.parkrelay.core.ZoneSignal
import app.parkrelay.core.distanceMeters
import app.parkrelay.core.OwnEvents
import app.parkrelay.core.message
import java.io.File
import java.util.UUID

/** Whether the car allows input right now. Not knowing is treated as not allowed. */
enum class DrivingState { UNKNOWN, RESTRICTED, UNRESTRICTED }

/**
 * Owns the parking session. It lives in a foreground service rather than a screen, because the app
 * is usually off screen: the navigation is the default view, reverse gear covers the display with
 * the camera, and a screen may be blocked while driving.
 *
 * All state changes happen on the main thread through [dispatch], one input at a time.
 */
class ParkingService : Service() {
    internal lateinit var settings: Settings
    internal lateinit var notify: NotifyCenter
    internal lateinit var voice: VoiceGuide
    internal lateinit var voiceInput: VoiceInput
    internal lateinit var navi: NaviSource
    internal lateinit var catalog: LotCatalog

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var reducer: SessionReducer
    private lateinit var store: SessionStore
    private lateinit var queue: EventQueue
    private lateinit var resolver: LotResolver
    private lateinit var advisor: LotAdvisor
    private lateinit var car: CarSignals
    private lateinit var vehicle: VehicleSignals
    private lateinit var location: LocationFeed

    private val http: Backend by lazy { HttpBackend { settings.serverUrl } }
    internal val demo: DemoBackend by lazy {
        DemoBackend { lotId -> catalog.byId(lotId)?.capacity }.also { it.onChanged = ::uiChanged }
    }
    private val backend: Backend get() = if (settings.demoMode) demo else http
    private var demoCarMoved = false
    private val fixes = FixHistory()
    private val adviceGate by lazy { AdviceGate(settings.advisedLot) { settings.advisedLot = it } }

    internal var state = SessionState()
        private set

    /** Until the car says otherwise, input is treated as not allowed. */
    internal var drivingState = DrivingState.UNKNOWN
        private set
    private val inputAllowed: Boolean get() = drivingState == DrivingState.UNRESTRICTED
    private var destination: Destination? = null
    private var targetLot: Lot? = null
    private var subscription: Backend.Subscription? = null
    private var guidedZone: String? = null
    private val speechGate = SpeechGate()
    private val ownEvents = OwnEvents()
    private var pendingPrompt: LotResolution? = null

    /** One microphone: [asking] is true from a question being queued until its answer or silence. */
    private var asking = false
    private var askToken: Any? = null

    private val approachReducer = ApproachReducer(newId = { UUID.randomUUID().toString() })
    private var approach: ApproachState? = null
    private var headedZone: String? = null
    private val refreshBoard = Runnable { fetchApproachBoard() }
    private val spaceCheck = Runnable { checkSpace() }

    /** True when the service was started without consent and is only on its way out. */
    private var idle = false
    private var erased = false

    /** The open screen, if any. The service never depends on one. */
    internal var onUiChange: (() -> Unit)? = null
    internal val approachLot: Lot? get() = targetLot
    internal val approachBoard: LotBoard? get() = approach?.board
    internal val approachNear: Boolean get() = approach?.phase == ApproachPhase.NEAR

    /** The zone this car said it is heading for, or was pointed to. */
    internal val headingZone: String? get() = approach?.claimedZone
    internal val headingPublished: Boolean get() = approach?.claim?.published == true
    internal val headingConfirmed: Boolean get() = approach?.claim?.confirmed == true
    internal var lastGuidance: String? = null
        private set

    /** For the debug probe only: straight into the offline queue, past the session. */
    internal fun enqueueForProbe(event: app.parkrelay.core.OutEvent) = queue.add(event)

    internal fun flushForProbe() = queue.flush()

    /** For the debug probe: one line a test script can read. */
    internal fun approachDebug(): String {
        val current = approach ?: return "none"
        val list = current.board?.let { app.parkrelay.core.BoardText.listText(it, current.claimedZone) }?.replace("\n", " / ")
        return "phase=${current.phase} near=${current.phase == ApproachPhase.NEAR} claim=${current.claimedZone} " +
            "published=${current.claim?.published} confirmed=${current.claim?.confirmed} inside=${current.inside} told=${current.listTold} list=[$list]"
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Trace.log("service", "screen on")
            reconcile()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Trace.log("service", "created uptimeFromBoot=${android.os.SystemClock.elapsedRealtime()}")
        settings = Settings(this)
        notify = NotifyCenter(this)
        notify.createChannels()
        // A service started in the foreground must show itself even if it is about to stop.
        goForeground()
        if (!settings.consent) {
            // No consent, no work: no restored session, no position, no queue. Whatever started us was mistaken.
            Trace.log("service", "no consent, stopping")
            idle = true
            stopSelf()
            return
        }
        instance = this

        catalog = loadCatalog(this)
        Trace.log("service", "catalog lots=${catalog.size}")
        resolver = LotResolver(catalog)
        advisor = LotAdvisor(catalog)
        store = SessionStore(this, catalog)
        state = store.load()
        queue = EventQueue(filesDir) { backend }
        reducer = SessionReducer(
            newId = { UUID.randomUUID().toString() },
            consent = { settings.consent },
            sharePreference = { settings.sharePreference(it) },
        )

        voice = VoiceGuide(this).also { it.start() }
        voiceInput = VoiceInput(this).also { it.start() }
        car = CarSignals(
            this,
            onGear = { gear ->
                if (gear != Gear.PARK) demoCarMoved = true
                post(Input.GearChanged(gear, now()))
            },
            onSpeed = { speed ->
                handler.post {
                    // Speed only matters while confirming a departure. Elsewhere it would just wear the disk.
                    if (state.phase == Phase.LEAVING || !state.speedSeen) dispatch(Input.Speed(speed, now()))
                }
            },
            onDrivingRestricted = { restricted ->
                handler.post {
                    drivingState = when (restricted) {
                        null -> DrivingState.UNKNOWN
                        true -> DrivingState.RESTRICTED
                        false -> DrivingState.UNRESTRICTED
                    }
                    Trace.log("ux", "restricted=$restricted state=$drivingState")
                    if (!inputAllowed) notify.cancelButtons()
                    uiChanged()
                }
            },
        )
        vehicle = VehicleSignals(
            this,
            onDriverDoor = { open -> post(Input.DriverDoor(open, now())) },
            onPassengerDoor = { open -> post(Input.PassengerDoor(open, now())) },
            onDriverSeat = { occupied -> post(Input.DriverSeat(occupied, now())) },
        )
        location = LocationFeed(
            this,
            onFix = { fix -> handler.post { onFix(fix) } },
            onExitedLot = { lotId -> handler.post { if (state.lot?.id == lotId) dispatch(Input.LeftLot(now())) } },
            // Inside the lot's own radius is certainly within a hundred metres of its edge.
            onArrivedAtLot = { lotId -> handler.post { arrivedAt(lotId, "proximity alert") } },
        )
        navi = NaviSource(
            this,
            onDestination = { place, _ -> handler.post { onDestination(place) } },
            onArrived = { handler.post { targetLot?.let { arrivedAt(it.id, "navigation") } } },
        )

        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), RECEIVER_NOT_EXPORTED)
        car.start()
        vehicle.start()
        location.start()
        navi.start()
        // Gives the car connection a moment, then accounts for anything missed while not running.
        handler.postDelayed({ reconcile() }, RECONCILE_DELAY_MILLIS)
        queue.flush()
    }

    private fun goForeground() {
        val notification = notify.foreground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotifyCenter.ID_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotifyCenter.ID_FOREGROUND, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Trace.log("service", "start action=${intent?.action}")
        if (idle) return START_NOT_STICKY
        intent?.let(::handleAction)
        return START_STICKY
    }

    /**
     * Every action here comes from a button. Buttons are only shown while input is allowed, but a tap
     * can arrive late, after the car has started moving, so the state is checked again where it acts.
     */
    private fun handleAction(intent: Intent) {
        val action = intent.action ?: return
        if (!inputAllowed) {
            Trace.log("ux", "ignored $action: input is not allowed now ($drivingState)")
            return
        }
        when (action) {
            ACTION_CONFIRM_ZONE -> {
                stopAsking()
                notify.cancelPrompt()
                dispatch(Input.ZoneAnswered(intent.getStringExtra(EXTRA_ZONE), now()))
            }

            ACTION_DECLINE_SHARE -> {
                stopAsking()
                notify.cancelPrompt()
                notify.cancelShare()
                dispatch(Input.ShareAnswered(false, now()))
            }

            ACTION_ALLOW_SHARE -> {
                notify.cancelShare()
                dispatch(Input.ShareAnswered(true, now()))
            }

            ACTION_PICK_LOT -> {
                notify.cancelPrompt()
                intent.getStringExtra(EXTRA_LOT)?.let(catalog::byId)?.let { lot ->
                    dispatch(Input.LotPicked(lot, now()))
                    notify.parkPrompt(lot, suggestedZone(lot))
                }
            }

            ACTION_ROUTE_TO_LOT -> {
                notify.cancelAdvice()
                intent.getStringExtra(EXTRA_LOT)?.let(catalog::byId)?.let(navi::routeTo)
            }

            ACTION_DISMISS -> {
                stopAsking()
                notify.cancelPrompt()
            }

            ACTION_DISMISS_ADVICE -> notify.cancelAdvice()
            ACTION_DECLINE_HEADING -> declineHeading()
            ACTION_MISSED_HEADING -> missedHeading()
            ACTION_PICK_HEADING -> intent.getStringExtra(EXTRA_ZONE)?.let(::pickHeading)
            ACTION_KEYPAD_RESULT -> {
                stopAsking()
                notify.cancelPrompt()
                dispatch(Input.ZoneAnswered(intent.getStringExtra(EXTRA_ZONE), now()))
            }
        }
    }

    /** The driver answered with a tap, so a question still being read and an open microphone are both pointless. */
    private fun stopAsking() {
        if (!asking) return
        asking = false
        askToken = null
        voice.interrupt()
        voiceInput.cancel()
    }

    private fun post(input: Input) {
        handler.post { dispatch(input) }
    }

    internal fun dispatch(input: Input) {
        if (erased) return
        val before = state.phase
        val lotBefore = state.lot
        val step = reducer.reduce(state, input)
        state = step.state
        store.save(state)
        if (state.phase != before) {
            Trace.log("session", "$before -> ${state.phase} by ${input::class.simpleName}")
            // Before the effects: the claim is released ahead of the PARK it leads to, so the server
            // never counts this car both as parked in a departure and as still heading for it.
            followSession(before, lotBefore)
            uiChanged()
        }
        step.effects.forEach(::apply)
    }

    private fun followSession(before: Phase, lotBefore: Lot?) {
        when (state.phase) {
            Phase.PARKED -> {
                approachInput(ApproachInput.Parked(now()))
                notify.cancelList()
            }
            // Back on the road with the destination still set: a new approach. A pause that never
            // became a parking (P_TENTATIVE) leaves the running approach as it is.
            Phase.DRIVING -> {
                val lot = targetLot
                if (lot != null && before != Phase.P_TENTATIVE && approach?.phase == ApproachPhase.ENDED) {
                    beginApproach(lot, leavingThisLot = lotBefore?.id == lot.id)
                }
            }
            else -> Unit
        }
    }

    private fun uiChanged() {
        handler.post { onUiChange?.invoke() }
    }

    /** Fetches a board for the screen. The answer arrives on the main thread. */
    internal fun loadBoard(lotId: String, done: (LotBoard?) -> Unit) {
        Thread({
            val board = backend.board(lotId)
            handler.post { done(board) }
        }, "board-load").start()
    }

    /** The server choice changed, so any open stream belongs to the old one, and so does any claim made there. */
    internal fun backendChanged() {
        stopApproach(publish = false)
        destination?.let(::onDestination)
        queue.flush()
        uiChanged()
    }

    /** Demo mode: heads for a real lot through the real navigation, so the rest of the flow is genuine. */
    internal fun startDemoRoute() {
        demoCarMoved = false
        catalog.byId(DEMO_LOT_ID)?.let(navi::routeTo)
    }

    /** Demo mode: another car leaves the lot this car is heading to. */
    internal fun simulateDeparture() {
        val lot = targetLot ?: catalog.byId(DEMO_LOT_ID)?.also(::startApproach) ?: return
        demo.simulateDeparture(lot.id, DEMO_ZONE, DEMO_DELAY_MILLIS)
    }

    /**
     * Demo mode: the emulator's car cannot drive, so this stands in for arriving a hundred metres from
     * the lot, where other cars (made up, and labelled so) have left spaces on several floors.
     */
    internal fun simulateNear() {
        if (!settings.demoMode) return
        val lot = targetLot ?: catalog.byId(DEMO_LOT_ID)?.also(::startApproach) ?: return
        demo.seedApproach(lot.id)
        if (approach?.phase != ApproachPhase.FAR) beginApproach(lot, leavingThisLot = false)
        Trace.log("approach", "demo: placed near ${Trace.detail(lot.id)}")
        approachInput(ApproachInput.Distance(DEMO_NEAR_METERS, now()))
    }

    /** Demo mode: stands in for driving into the lot, where the zone this car is heading for is confirmed. */
    internal fun simulateArrive() {
        if (!settings.demoMode) return
        if (approach?.phase != ApproachPhase.NEAR) simulateNear()
        Trace.log("approach", "demo: placed inside the lot")
        approachInput(ApproachInput.Distance(DEMO_INSIDE_METERS, now()))
    }

    /** The driver switched sharing for a lot. It applies to the parking in progress, not only to the next one. */
    internal fun shareChanged(lotId: String, allow: Boolean) {
        if (state.lot?.id == lotId) dispatch(Input.ShareAnswered(allow, now()))
        if (targetLot?.id == lotId) restartApproach()
    }

    /** "Tell others my zone" was switched, so the running approach starts over under the new rule. */
    internal fun headingSettingChanged() = restartApproach()

    private fun restartApproach() {
        stopApproach(publish = true)
        destination?.let(::onDestination)
    }

    internal fun pickHeading(zoneId: String) {
        notify.cancelList()
        approachInput(ApproachInput.Pick(zoneId, now()))
    }

    internal fun declineHeading() {
        notify.cancelList()
        approachInput(ApproachInput.Decline(now()))
    }

    /** No space in the zone this car was heading for: that ends at once and the next zone is offered. */
    internal fun missedHeading() {
        notify.cancelList()
        approachInput(ApproachInput.Missed(now()))
    }

    /**
     * There is no microphone that listens all the time. So a while after the zone was confirmed, if the
     * car is still driving, it is asked once, and "자리 없어요" is heard then.
     */
    internal fun checkSpace() {
        val zone = approach?.claim?.takeIf { it.confirmed }?.zoneId ?: return
        if (state.phase != Phase.DRIVING || asking || state.gear == Gear.REVERSE || !settings.voiceQuestions) return
        Trace.log("approach", "asking whether there was a space")
        ask("${Guidance.spokenZone(zone)}에 자리가 없으면 '자리 없어요'라고 말씀해 주세요.", ::onHeadingSpeech)
    }

    /**
     * Consent withdrawn. Everything this app keeps on the car goes, and nothing still queued is sent.
     * The caller clears the settings and stops the service.
     */
    internal fun eraseEverything() {
        Trace.log("service", "erasing local data")
        stopAsking()
        voice.silence()
        voiceInput.cancel()
        stopApproach(publish = false)
        queue.close()
        handler.removeCallbacksAndMessages(null)
        location.stopWatchingExit()
        notify.cancelAll()
        erased = true
        state = SessionState()
        eraseFiles(this)
    }

    private fun apply(effect: Effect) {
        when (effect) {
            is Effect.Publish -> publish(effect.event)

            Effect.ResolveLot -> resolveLot()

            is Effect.WakeAt -> {
                val delay = (effect.atMillis - now()).coerceAtLeast(0)
                handler.postDelayed({ dispatch(Input.Tick(now())) }, delay)
            }

            is Effect.PromptPark -> promptPark(effect.resolution, effect.askShare)
            Effect.CancelPrompt -> {
                stopAsking()
                notify.cancelPrompt()
            }

            is Effect.PromptDeparture -> catalog.byId(effect.lotId)?.let(::promptDeparture)

            is Effect.SaveCarLocation -> {
                val name = catalog.byId(effect.lotId)?.name.orEmpty()
                settings.saveCarLocation(effect.lotId, name, effect.zoneId, effect.atMillis)
            }

            is Effect.WatchLot -> catalog.byId(effect.lotId)?.let(location::watchExit)
            Effect.StopWatchingLot -> location.stopWatchingExit()
            is Effect.RememberShare -> settings.rememberShare(effect.lotId, effect.allow)
            is Effect.RememberLot -> settings.rememberLot(effect.lotId)
        }
    }

    private fun publish(event: app.parkrelay.core.OutEvent) {
        Trace.log("publish", "${event.type} ${Trace.detail("lot=${event.lotId} zone=${event.zoneId}")}")
        ownEvents.add(event.eventId)
        queue.add(event)
    }

    private fun resolveLot() {
        val at = now()
        // The demo's car cannot actually drive there, so parking counts as having arrived. The screen says this is a demo.
        // Sitting in P with a route left from before is not arriving, and would hold back the news the demo is about.
        val demoLot = targetLot?.takeIf { settings.demoMode && demoCarMoved }
        val resolution = demoLot?.let { LotResolution.Single(it, Confidence.HIGH) }
            ?: resolver.resolve(destination, settings.confirmedLots(), fixes.last, at)
        val search = (resolution as? LotResolution.Single)?.let { fixes.searchSeconds(it.lot, at) }
        Trace.log("lot", "resolved ${Trace.detail(resolution)} search=$search")
        dispatch(Input.Resolved(resolution, search, at))
    }

    private fun promptPark(resolution: LotResolution, askShare: Boolean) {
        pendingPrompt = resolution
        // Parking has begun: the list has done its job, and a question still open belongs to the drive.
        notify.cancelList()
        stopAsking()
        when (resolution) {
            is LotResolution.Ambiguous -> notify.lotChoice(resolution.candidates)
            is LotResolution.Single -> {
                val lot = resolution.lot
                val zone = suggestedZone(lot)
                notify.parkPrompt(lot, zone)
                if (askShare) notify.shareQuestion(lot)
                // Asked aloud only at a lot this car has not used before. A regular gets the notification.
                if (settings.voiceQuestions && lot.id !in settings.confirmedLots()) askZoneByVoice(zone)
            }

            LotResolution.None -> Unit
        }
    }

    private fun promptDeparture(lot: Lot) {
        notify.departurePrompt(lot)
        askZone("지금 어느 구역에서 출발하세요?", null)
    }

    private fun askZoneByVoice(suggested: String?) = askZone(Guidance.parkQuestion(suggested), suggested)

    private fun askZone(question: String, suggested: String?) = ask(question) { text -> onZoneSpeech(text, suggested) }

    /**
     * Every spoken question goes through here, because there is one microphone. It speaks, and only
     * after the question was heard to its end opens the microphone, so the car does not hear itself.
     * A newer question replaces an older one. While one is open nothing else is spoken: a sentence
     * naming a zone would be picked up as the driver's answer.
     */
    private fun ask(question: String, onHeard: (String?) -> Unit) {
        voiceInput.cancel()
        val token = Any()
        asking = true
        askToken = token
        voice.say(question) { spoken ->
            if (askToken !== token) return@say
            if (!spoken) {
                // Cut off by a call or another announcement: the driver never heard the question.
                asking = false
                askToken = null
                return@say
            }
            voiceInput.listenOnce { text ->
                handler.post {
                    if (askToken !== token) return@post
                    asking = false
                    askToken = null
                    onHeard(text)
                }
            }
        }
    }

    private fun onZoneSpeech(text: String?, suggested: String?) {
        if (text == null) return
        when (val answer = ZoneParser.parse(text)) {
            is SpokenAnswer.Zone -> confirmZone(answer.zoneId)
            SpokenAnswer.Yes -> suggested?.let(::confirmZone)
            SpokenAnswer.No, SpokenAnswer.Full, SpokenAnswer.NotUnderstood -> Unit
        }
    }

    private fun confirmZone(zoneId: String) {
        notify.cancelPrompt()
        dispatch(Input.ZoneAnswered(zoneId, now()))
        voice.say("${Guidance.spokenZone(zoneId)}에 저장했어요.")
    }

    /**
     * The zone this car said it was heading for is the likeliest answer, then a zone just announced
     * for this lot, then where this car parked here last.
     */
    private fun suggestedZone(lot: Lot): String? {
        val heading = targetLot?.id == lot.id
        return headedZone.takeIf { heading } ?: guidedZone.takeIf { heading } ?: settings.lastZone(lot.id)
    }

    private fun onFix(fix: Fix) {
        fixes.add(fix)
        feedDistance(fix)
        val lot = state.lot ?: return
        if (state.phase != Phase.LEAVING && state.phase != Phase.VACATED) return
        val away = distanceMeters(fix.lat, fix.lng, lot.lat, lot.lng) > lot.radiusM + EXIT_MARGIN_METERS
        if (away) dispatch(Input.LeftLot(now()))
    }

    private fun onDestination(place: Destination?) {
        destination = place
        if (place == null) {
            stopApproach(publish = true)
            return
        }
        val advice = advisor.advise(place)
        Trace.log("approach", "destination=${Trace.detail(place.name)} advice=${Trace.detail(advice)}")
        val lot = when (advice) {
            is LotAdvice.AtDestination -> advice.lot
            is LotAdvice.Nearby -> advice.lot
            null -> null
        }
        if (advice != null && adviceGate.shouldAnnounce(place, advice)) announce(advice)
        if (lot != null) startApproach(lot) else stopApproach(publish = true)
        uiChanged()
    }

    private fun announce(advice: LotAdvice) {
        val routable = (advice as? LotAdvice.Nearby)?.lot
        when {
            routable == null -> {
                notify.lotAdvice(advice.message(), null)
                voice.say(advice.message())
            }

            // Stopped: a button is safe. Moving: ask aloud and take a spoken yes, with no tap involved.
            inputAllowed -> {
                notify.lotAdvice(advice.message(), routable)
                voice.say(advice.message())
            }

            else -> {
                notify.lotAdvice(advice.message(), null)
                ask("${advice.message()} 안내할까요?") { text ->
                    if (text != null && ZoneParser.parse(text) == SpokenAnswer.Yes) navi.routeTo(routable)
                }
            }
        }
    }

    // --- Approach: the list of zones a hundred metres out, and saying where this car is heading ---

    private fun startApproach(lot: Lot) {
        if (targetLot?.id == lot.id && subscription != null) return
        stopApproach(publish = true)
        targetLot = lot
        location.watchArrival(lot)
        subscription = backend.subscribe(lot.id) { signal -> handler.post { onSignal(signal) } }
        Trace.log("approach", "watching ${Trace.detail(lot.id)}")
        // A car already parked in this very lot is not arriving; it gets an approach when it drives again.
        beginApproach(lot, leavingThisLot = state.lot?.id == lot.id && state.phase != Phase.DRIVING)
    }

    private fun beginApproach(lot: Lot, leavingThisLot: Boolean) {
        headedZone = null
        approach = approachReducer.start(lot, mayPublish = mayShareHeading(lot), leavingThisLot = leavingThisLot)
        scheduleBoardRefresh(0)
        fixes.last?.takeIf { now() - it.atMillis <= FRESH_FIX_MILLIS }?.let(::feedDistance)
    }

    /** The same rule as for sharing a parking: consent, a lot that may be shared, and the driver's own switch. */
    private fun mayShareHeading(lot: Lot): Boolean {
        if (!settings.consent || !settings.shareHeading || lot.tier == ShareTier.NEVER) return false
        return settings.sharePreference(lot.id) ?: (lot.tier == ShareTier.AUTO)
    }

    private fun stopApproach(publish: Boolean) {
        approach?.let { current ->
            approachReducer.reduce(current, ApproachInput.Stopped(publish, now())).effects.forEach(::applyApproach)
        }
        approach = null
        headedZone = null
        handler.removeCallbacks(refreshBoard)
        handler.removeCallbacks(spaceCheck)
        notify.cancelList()
        subscription?.cancel()
        subscription = null
        targetLot = null
        guidedZone = null
        lastGuidance = null
        location.stopWatchingArrival()
    }

    private fun approachInput(input: ApproachInput) {
        val current = approach ?: return
        val step = approachReducer.reduce(current, input)
        approach = step.state
        if (step.state.phase != current.phase) {
            // "near" is what the test scripts wait for.
            val name = step.state.phase.name.lowercase()
            Trace.log("approach", "$name ${Trace.detail(current.lot.id)} by ${input::class.simpleName}")
        }
        if (step.state.phase != ApproachPhase.ENDED) headedZone = step.state.claimedZone
        val confirmedBefore = current.claim?.takeIf { it.confirmed }?.zoneId
        val confirmedNow = step.state.claim?.takeIf { it.confirmed }?.zoneId
        if (confirmedNow != confirmedBefore) {
            handler.removeCallbacks(spaceCheck)
            if (confirmedNow != null) handler.postDelayed(spaceCheck, SPACE_CHECK_MILLIS)
        }
        step.effects.forEach(::applyApproach)
        if (step.state != current) uiChanged()
    }

    private fun applyApproach(effect: ApproachEffect) {
        when (effect) {
            ApproachEffect.FetchBoard -> scheduleBoardRefresh(0)
            is ApproachEffect.Publish -> publish(effect.event)
            is ApproachEffect.TellList -> tellList(effect)
            is ApproachEffect.Tell -> tell(effect.sentence)
        }
    }

    /** Only a car on the road is approaching. A parked one keeps its distance to itself. */
    private fun feedDistance(fix: Fix) {
        val lot = targetLot ?: return
        if (state.phase != Phase.DRIVING) return
        val toEdge = distanceMeters(fix.lat, fix.lng, lot.lat, lot.lng) - lot.radiusM
        approachInput(ApproachInput.Distance(toEdge, now()))
    }

    private fun arrivedAt(lotId: String, source: String) {
        Trace.log("approach", "arrived by $source")
        if (targetLot?.id != lotId || state.phase != Phase.DRIVING) return
        approachInput(ApproachInput.Distance(0.0, now()))
    }

    /** Several signals often arrive together; one fetch a moment later answers them all. */
    private fun scheduleBoardRefresh(delayMillis: Long = BOARD_DEBOUNCE_MILLIS) {
        handler.removeCallbacks(refreshBoard)
        handler.postDelayed(refreshBoard, delayMillis)
    }

    private fun fetchApproachBoard() {
        val lot = targetLot ?: return
        if (approach?.phase == ApproachPhase.ENDED) return
        loadBoard(lot.id) { board ->
            if (targetLot?.id != lot.id) return@loadBoard
            // With no network at the entrance, the list is still told, from what was last known.
            val known = board ?: approach?.takeIf { it.phase == ApproachPhase.NEAR && !it.listTold }?.board
            // While the car is parking the board is not needed, and telling the list then would talk over the prompt.
            if (known != null && state.phase == Phase.DRIVING) approachInput(ApproachInput.Board(known, now()))
            // The stream has no replay, so a missed signal is caught by asking again while it matters.
            if (approach?.phase == ApproachPhase.NEAR) scheduleBoardRefresh(NEAR_REFRESH_MILLIS)
        }
    }

    private fun tellList(list: ApproachEffect.TellList) {
        lastGuidance = null
        val canChange = inputAllowed && list.claimedZone != null
        notify.zoneList(list.title, list.text, withButtons = canChange)
        if (state.gear == Gear.REVERSE) return
        voice.say(list.spoken)
        val followUp = list.followUp ?: return
        // What was just said to other cars can be changed by naming another zone, or withdrawn.
        if (headingPublished && settings.voiceQuestions) ask(followUp, ::onHeadingSpeech) else voice.say(followUp)
    }

    private fun onHeadingSpeech(text: String?) {
        when (val answer = text?.let(ZoneParser::parse)) {
            is SpokenAnswer.Zone -> approachInput(ApproachInput.Pick(answer.zoneId, now()))
            SpokenAnswer.No -> approachInput(ApproachInput.Decline(now()))
            SpokenAnswer.Full -> approachInput(ApproachInput.Missed(now()))
            else -> Unit
        }
    }

    private fun tell(sentence: String) {
        lastGuidance = sentence
        uiChanged()
        notify.guide(sentence)
        if (state.gear != Gear.REVERSE && !asking) voice.say(sentence)
    }

    /** A signal about the lot this car is heading to. Only a car looking for a space has any use for it. */
    private fun onSignal(signal: ZoneSignal) {
        Trace.log("signal", "$signal phase=${state.phase}")
        // Whatever it is, the list may have changed, this car's own events included.
        if (approach != null && approach?.phase != ApproachPhase.ENDED) scheduleBoardRefresh()
        if (state.phase != Phase.DRIVING || ownEvents.isOwn(signal)) return
        // A door opening somewhere is a hint for the operator's dashboard, not something to say to a driver.
        if (signal.weak) return
        val sentence = Guidance.sentence(signal) ?: return
        if (approach?.speaksSignals == false) {
            Trace.log("approach", "quiet: the list comes first, nearer the lot")
            return
        }
        if (signal.type == EventType.VACATED) guidedZone = signal.zoneId
        lastGuidance = sentence
        uiChanged()
        notify.guide(sentence)
        val reversing = state.gear == Gear.REVERSE
        if (!reversing && !asking && speechGate.allow(signal, now())) voice.say(sentence)
    }

    /** Runs at start and on screen on, since suspend may end with no boot event at all. */
    internal fun reconcile() {
        if (!car.isConnected()) {
            Trace.log("service", "reconcile: car not connected yet")
            handler.postDelayed({ reconcile() }, RECONCILE_DELAY_MILLIS)
            return
        }
        val inside = insideCurrentLot()
        Trace.log("service", "reconcile gear=${car.currentGear()} inside=$inside phase=${state.phase}")
        dispatch(Input.Woke(car.currentGear(), inside, now()))
        queue.flush()
    }

    private fun insideCurrentLot(): Boolean? {
        val lot = state.lot ?: return null
        val fix = fixes.last ?: return null
        return distanceMeters(fix.lat, fix.lng, lot.lat, lot.lng) <= lot.radiusM + EXIT_MARGIN_METERS
    }

    override fun onDestroy() {
        Trace.log("service", "destroyed")
        if (idle) {
            super.onDestroy()
            return
        }
        instance = null
        handler.removeCallbacksAndMessages(null)
        Trace.guarded("service stop") { unregisterReceiver(screenReceiver) }
        subscription?.cancel()
        car.stop()
        vehicle.stop()
        location.stop()
        navi.stop()
        voice.stop()
        voiceInput.stop()
        super.onDestroy()
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val ACTION_CONFIRM_ZONE = "app.parkrelay.CONFIRM_ZONE"
        const val ACTION_KEYPAD_RESULT = "app.parkrelay.KEYPAD_RESULT"
        const val ACTION_DECLINE_SHARE = "app.parkrelay.DECLINE_SHARE"
        const val ACTION_ALLOW_SHARE = "app.parkrelay.ALLOW_SHARE"
        const val ACTION_PICK_LOT = "app.parkrelay.PICK_LOT"
        const val ACTION_ROUTE_TO_LOT = "app.parkrelay.ROUTE_TO_LOT"
        const val ACTION_DISMISS = "app.parkrelay.DISMISS"
        const val ACTION_DISMISS_ADVICE = "app.parkrelay.DISMISS_ADVICE"
        const val ACTION_DECLINE_HEADING = "app.parkrelay.DECLINE_HEADING"
        const val ACTION_MISSED_HEADING = "app.parkrelay.MISSED_HEADING"
        const val ACTION_PICK_HEADING = "app.parkrelay.PICK_HEADING"
        const val EXTRA_ZONE = "zone"
        const val EXTRA_LOT = "lot"

        private const val RECONCILE_DELAY_MILLIS = 2_000L
        private const val EXIT_MARGIN_METERS = 50
        private const val DEMO_LOT_ID = "59446-29391-00005-00-1" // 코엑스 주차장, a real lot.
        private const val DEMO_ZONE = "B2-B"                     // Made up. The catalog has no zones.
        private const val DEMO_DELAY_MILLIS = 10_000L
        private const val DEMO_NEAR_METERS = 80.0
        private const val DEMO_INSIDE_METERS = -1.0
        private const val SPACE_CHECK_MILLIS = 90_000L
        private const val BOARD_DEBOUNCE_MILLIS = 300L
        private const val NEAR_REFRESH_MILLIS = 15_000L
        private const val FRESH_FIX_MILLIS = 30_000L

        internal var instance: ParkingService? = null
            private set

        /** The session and the unsent events. Called with or without a running service. */
        fun eraseFiles(context: Context) {
            listOf("session.json", "session.json.tmp", EventQueue.FILE_NAME, "${EventQueue.FILE_NAME}.tmp")
                .forEach { File(context.filesDir, it).delete() }
        }

        /**
         * Android refuses a foreground start from most background contexts. That must not crash the
         * app: the service starts on the next boot or the next time the driver opens the app.
         */
        fun start(context: Context) {
            if (!Settings(context).consent) return
            try {
                context.startForegroundService(Intent(context, ParkingService::class.java))
            } catch (e: IllegalStateException) {
                Trace.log("service", "start refused: $e")
            }
        }
    }
}
