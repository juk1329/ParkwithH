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
import app.parkrelay.core.SpeechGate
import app.parkrelay.core.SpokenAnswer
import app.parkrelay.core.ZoneParser
import app.parkrelay.core.ZoneSignal
import app.parkrelay.core.distanceMeters
import app.parkrelay.core.OwnEvents
import app.parkrelay.core.message
import java.util.UUID

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
    private var drivingRestricted = false
    private var destination: Destination? = null
    private var targetLot: Lot? = null
    private var subscription: Backend.Subscription? = null
    private var guidedZone: String? = null
    private val speechGate = SpeechGate()
    private val ownEvents = OwnEvents()
    private var pendingPrompt: LotResolution? = null
    private var asking = false

    /** The open screen, if any. The service never depends on one. */
    internal var onUiChange: (() -> Unit)? = null
    internal val isDriving: Boolean get() = drivingRestricted
    internal val approachLot: Lot? get() = targetLot
    internal var lastGuidance: String? = null
        private set

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Trace.log("service", "screen on")
            reconcile()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Trace.log("service", "created uptimeFromBoot=${android.os.SystemClock.elapsedRealtime()}")
        settings = Settings(this)
        notify = NotifyCenter(this)
        notify.createChannels()
        goForeground()

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
                    Trace.log("ux", "restricted=$restricted")
                    drivingRestricted = restricted
                    if (restricted) notify.cancelButtons()
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
            onArrivedAtLot = { lotId -> Trace.log("approach", "arrived near $lotId") },
        )
        navi = NaviSource(
            this,
            onDestination = { place, _ -> handler.post { onDestination(place) } },
            onArrived = { Trace.log("approach", "navigation arrived") },
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
        intent?.let(::handleAction)
        return START_STICKY
    }

    private fun handleAction(intent: Intent) {
        when (intent.action) {
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
            ACTION_KEYPAD_RESULT -> {
                stopAsking()
                notify.cancelPrompt()
                dispatch(Input.ZoneAnswered(intent.getStringExtra(EXTRA_ZONE), now()))
            }
        }
    }

    /** The driver answered with a tap, so a question still being read and an open microphone are both pointless. */
    private fun stopAsking() {
        asking = false
        voice.interrupt()
        voiceInput.cancel()
    }

    private fun post(input: Input) {
        handler.post { dispatch(input) }
    }

    internal fun dispatch(input: Input) {
        val before = state.phase
        val step = reducer.reduce(state, input)
        state = step.state
        store.save(state)
        if (state.phase != before) {
            Trace.log("session", "$before -> ${state.phase} by ${input::class.simpleName}")
            uiChanged()
        }
        step.effects.forEach(::apply)
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

    /** The server choice changed, so any open stream belongs to the old one. */
    internal fun backendChanged() {
        stopApproach()
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

    private fun apply(effect: Effect) {
        when (effect) {
            is Effect.Publish -> {
                Trace.log("publish", "${effect.event.type} lot=${effect.event.lotId} zone=${effect.event.zoneId}")
                ownEvents.add(effect.event.eventId)
                queue.add(effect.event)
            }

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

    private fun resolveLot() {
        val at = now()
        // The demo's car cannot actually drive there, so parking counts as having arrived. The screen says this is a demo.
        // Sitting in P with a route left from before is not arriving, and would hold back the news the demo is about.
        val demoLot = targetLot?.takeIf { settings.demoMode && demoCarMoved }
        val resolution = demoLot?.let { LotResolution.Single(it, Confidence.HIGH) }
            ?: resolver.resolve(destination, settings.confirmedLots(), fixes.last, at)
        val search = (resolution as? LotResolution.Single)?.let { fixes.searchSeconds(it.lot, at) }
        Trace.log("lot", "resolved $resolution search=$search")
        dispatch(Input.Resolved(resolution, search, at))
    }

    private fun promptPark(resolution: LotResolution, askShare: Boolean) {
        pendingPrompt = resolution
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
        ask("지금 어느 구역에서 출발하세요?", null)
    }

    private fun askZoneByVoice(suggested: String?) = ask(Guidance.parkQuestion(suggested), suggested)

    /** Speaks, and only then opens the microphone, so the car does not hear its own question. */
    private fun ask(question: String, suggested: String?) {
        asking = true
        voice.say(question) {
            if (asking) {
                voiceInput.listenOnce { text ->
                    handler.post {
                        asking = false
                        onZoneSpeech(text, suggested)
                    }
                }
            }
        }
    }

    private fun onZoneSpeech(text: String?, suggested: String?) {
        if (text == null) return
        when (val answer = ZoneParser.parse(text)) {
            is SpokenAnswer.Zone -> confirmZone(answer.zoneId)
            SpokenAnswer.Yes -> suggested?.let(::confirmZone)
            SpokenAnswer.No, SpokenAnswer.NotUnderstood -> Unit
        }
    }

    private fun confirmZone(zoneId: String) {
        notify.cancelPrompt()
        dispatch(Input.ZoneAnswered(zoneId, now()))
        voice.say("${Guidance.spokenZone(zoneId)}에 저장했어요.")
    }

    /** A zone just announced for this lot is the likeliest answer; otherwise where this car parked last. */
    private fun suggestedZone(lot: Lot): String? =
        guidedZone.takeIf { targetLot?.id == lot.id } ?: settings.lastZone(lot.id)

    private fun onFix(fix: Fix) {
        fixes.add(fix)
        val lot = state.lot ?: return
        if (state.phase != Phase.LEAVING && state.phase != Phase.VACATED) return
        val away = distanceMeters(fix.lat, fix.lng, lot.lat, lot.lng) > lot.radiusM + EXIT_MARGIN_METERS
        if (away) dispatch(Input.LeftLot(now()))
    }

    private fun onDestination(place: Destination?) {
        destination = place
        if (place == null) {
            stopApproach()
            return
        }
        val advice = advisor.advise(place)
        Trace.log("approach", "destination=${place.name} advice=$advice")
        val lot = when (advice) {
            is LotAdvice.AtDestination -> advice.lot
            is LotAdvice.Nearby -> advice.lot
            null -> null
        }
        if (advice != null && adviceGate.shouldAnnounce(place, advice)) announce(advice)
        if (lot != null) startApproach(lot) else stopApproach()
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
            !drivingRestricted -> {
                notify.lotAdvice(advice.message(), routable)
                voice.say(advice.message())
            }

            else -> {
                notify.lotAdvice(advice.message(), null)
                voice.say("${advice.message()} 안내할까요?") {
                    voiceInput.listenOnce { text ->
                        handler.post {
                            if (text != null && ZoneParser.parse(text) == SpokenAnswer.Yes) navi.routeTo(routable)
                        }
                    }
                }
            }
        }
    }

    private fun startApproach(lot: Lot) {
        if (targetLot?.id == lot.id && subscription != null) return
        stopApproach()
        targetLot = lot
        location.watchArrival(lot)
        subscription = backend.subscribe(lot.id) { signal -> handler.post { onSignal(signal) } }
        Trace.log("approach", "watching ${lot.id}")
    }

    private fun stopApproach() {
        subscription?.cancel()
        subscription = null
        targetLot = null
        guidedZone = null
        lastGuidance = null
        location.stopWatchingArrival()
    }

    /** A signal about the lot this car is heading to. Only a car looking for a space has any use for it. */
    private fun onSignal(signal: ZoneSignal) {
        Trace.log("signal", "$signal phase=${state.phase}")
        if (state.phase != Phase.DRIVING || ownEvents.isOwn(signal)) return
        val sentence = Guidance.sentence(signal) ?: return
        if (signal.type == EventType.VACATED) guidedZone = signal.zoneId
        lastGuidance = sentence
        uiChanged()
        notify.guide(sentence)
        val reversing = state.gear == Gear.REVERSE
        if (!reversing && speechGate.allow(signal, now())) voice.say(sentence)
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
        const val EXTRA_ZONE = "zone"
        const val EXTRA_LOT = "lot"

        private const val RECONCILE_DELAY_MILLIS = 2_000L
        private const val EXIT_MARGIN_METERS = 50
        private const val DEMO_LOT_ID = "59446-29391-00005-00-1" // 코엑스 주차장, a real lot.
        private const val DEMO_ZONE = "B2-B"                     // Made up. The catalog has no zones.
        private const val DEMO_DELAY_MILLIS = 10_000L

        internal var instance: ParkingService? = null
            private set

        /**
         * Android refuses a foreground start from most background contexts. That must not crash the
         * app: the service starts on the next boot or the next time the driver opens the app.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ParkingService::class.java))
            } catch (e: IllegalStateException) {
                Trace.log("service", "start refused: $e")
            }
        }
    }
}
