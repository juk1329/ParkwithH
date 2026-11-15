package app.parkrelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.parkrelay.core.BoardText
import app.parkrelay.core.FloorGroup
import app.parkrelay.core.Guidance
import app.parkrelay.core.Lot
import app.parkrelay.core.LotBoard
import app.parkrelay.core.ZoneParser
import app.parkrelay.core.ZoneRow
import app.parkrelay.core.approximateCapacity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The secondary path to the driver. The app is off screen by default, so nothing depends on this
 * screen being seen. While driving it is a card to glance at, with nothing to touch. In P it holds
 * the car's location, the list of zones, the keypad and the settings.
 *
 * The look borrows from the pillars of a parking garage: the floor painted on a coloured band, the
 * zone as one large letter, and beside it the one number that matters. Colour carries meaning only:
 * green for a departure nobody is heading for, amber for a car about to leave, grey for one already
 * spoken for, and the blue of a parking sign for this car's own choice.
 *
 * Every layout here works at any window width: rows wrap, and the page scrolls.
 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var palette: Palette
    private lateinit var uxWatch: UxWatch
    private val handler = Handler(Looper.getMainLooper())
    private var floor: String? = null
    private var askedPermissions = false

    /** Asked of the car directly: the service may not exist yet, and "unknown" must never show buttons. */
    private var driving = DrivingState.UNKNOWN

    /** Boards of lots this car is parked in. The approach board comes from the service, already fresh. */
    private val boards = mutableMapOf<String, LotBoard?>()
    private val boardLoadedAt = mutableMapOf<String, Long>()

    private val service get() = ParkingService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        palette = if (night) Palette.NIGHT else Palette.DAY
        window.decorView.setBackgroundColor(palette.background)
        // A resized window recreates the activity; the floor already chosen on the keypad survives it.
        floor = savedInstanceState?.getString(STATE_FLOOR)
        uxWatch = UxWatch(this) { state ->
            handler.post {
                driving = state
                render()
            }
        }
        applyLaunchMode(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FLOOR, floor)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchMode(intent)
        render()
    }

    /**
     * The two-car demo console starts the app with `--es mode server`, so nobody has to find the
     * settings button on two screens. Only the two fixed modes are accepted, never an address, and the
     * extra is removed once used so a recreated activity cannot undo a later choice in the settings.
     */
    private fun applyLaunchMode(intent: Intent?) {
        val demo = when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_SERVER -> false
            MODE_DEMO -> true
            else -> return
        }
        intent.removeExtra(EXTRA_MODE)
        if (settings.demoMode == demo) return
        settings.demoMode = demo
        service?.backendChanged()
    }

    override fun onResume() {
        super.onResume()
        driving = DrivingState.UNKNOWN
        uxWatch.start()
        if (settings.consent) startWhenPermitted()
        render()
        follow()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        service?.onUiChange = null
        uxWatch.stop()
        super.onPause()
    }

    /**
     * A signal registered without its permission does not recover by itself, so the service waits
     * for the driver's answer. Asks once per visit: a refusal must not bring the dialog straight back.
     */
    private fun startWhenPermitted() {
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty() || askedPermissions) {
            ParkingService.start(this)
            return
        }
        askedPermissions = true
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!settings.consent) return
        // A service already started at boot registered without these, so it has to begin again.
        if (service != null) stopService(Intent(this, ParkingService::class.java))
        ParkingService.start(this)
        follow()
    }

    /** The service may still be starting, so this keeps trying until it can listen for changes. */
    private fun follow() {
        if (!settings.consent) return
        val running = service
        if (running == null) {
            handler.postDelayed({
                follow()
                if (service != null) render()
            }, SERVICE_RETRY_MILLIS)
        } else {
            running.onUiChange = ::render
        }
    }

    private fun render() {
        when {
            // Until the car answers, nothing that can be touched is shown.
            driving == DrivingState.UNKNOWN -> showWaiting()
            driving == DrivingState.RESTRICTED -> if (settings.consent) showDrivingCard() else showParkFirst()
            !settings.consent -> showConsent()
            intent?.action == ACTION_KEYPAD -> showKeypad()
            intent?.action == ACTION_PICK_HEADING -> showHeadingPicker()
            else -> showHome()
        }
    }

    private fun showWaiting() {
        val root = page()
        root.addView(title("차량 상태를 확인하고 있어요"))
        root.addView(body("주행 중인지 확인되면 화면이 바뀌어요. 그 전에는 누를 수 있는 것을 보여 주지 않아요."))
        show(root)
    }

    private fun showParkFirst() {
        val root = page()
        root.addView(title("정차한 뒤에 시작할 수 있어요"))
        root.addView(body("기어를 P에 두면 안내와 동의 화면이 나와요."))
        show(root)
    }

    private fun showConsent() {
        val root = page()
        root.addView(title("주차장 앞에서 어느 층 어느 구역으로 갈지 알려 드려요"))
        root.addView(body(CONSENT_TEXT))
        root.addView(primary("동의하고 시작") {
            settings.consent = true
            startWhenPermitted()
            render()
            follow()
        }, spaced())
        show(root)
    }

    /** Glanceable and untouchable: no buttons, no scrolling needed, nothing that moves. */
    private fun showDrivingCard() {
        val root = page()
        addDemoNotice(root)
        val running = service
        val lot = running?.approachLot
        if (running == null || lot == null) {
            root.addView(title("안내할 주차장이 없어요"))
            root.addView(body("내비 목적지가 주차장 근처면 100m 앞에서 주차 가능 구역을 음성으로 알려 드려요."))
            show(root)
            return
        }
        root.addView(lotHeader(lot))
        val heading = running.headingZone
        if (heading != null) {
            val label = when {
                running.headingConfirmed -> "확정한 내 구역"
                running.headingPublished -> "뒤차에 알린 내 구역"
                else -> "권하는 구역"
            }
            root.addView(body(label))
            root.addView(text(Guidance.spokenZone(heading).orEmpty(), 76f, palette.mine, bold = true))
        }
        val board = running.approachBoard
        val floors = board?.let { BoardText.byFloor(it, heading, DRIVING_ROWS) }.orEmpty()
        if (floors.isEmpty()) {
            root.addView(text(BoardText.NO_NEWS, 48f, palette.text, bold = true))
            root.addView(body("소식이 들어오면 음성으로 알려 드려요."))
        } else {
            root.addView(text(BoardText.LIST_TITLE, 40f, palette.text, bold = true))
            root.addView(zoneList(floors, heading, recommended(board, heading), large = true, onPick = null))
            running.lastGuidance?.let { root.addView(text(it, 34f, palette.text)) }
            root.addView(body(BoardText.CAVEAT))
        }
        show(root)
    }

    private fun showHome() {
        val root = page()
        addDemoNotice(root)
        addCarLocation(root)
        boardLot()?.let { addBoard(root, it) }
        addDemoControls(root)
        addSettings(root)
        show(root)
    }

    private fun addDemoNotice(root: LinearLayout) {
        if (!settings.demoMode) return
        root.addView(TextView(this).apply {
            text = "데모 모드 · 다른 차량과 구역 이름은 예시이고, 서버로 아무것도 보내지 않아요"
            textSize = 22f
            setTextColor(Color.WHITE)
            background = rounded(palette.notice, dp(12))
            setPadding(dp(GAP), dp(GAP / 2), dp(GAP), dp(GAP / 2))
            breakByPhrase()
        }, spaced())
    }

    private fun addCarLocation(root: LinearLayout) {
        val card = card()
        card.addView(caption("내 차 위치"))
        val saved = settings.carLocation()
        if (saved == null) {
            card.addView(text("아직 저장된 위치가 없어요", 40f, palette.text, bold = true))
            card.addView(body("주차하면 알림으로 물어볼게요. 한 번 누르면 저장돼요."))
        } else {
            val zone = saved.optString("zone").takeIf { it.isNotEmpty() && it != "null" }
            card.addView(text(Guidance.spokenZone(zone) ?: "구역 미입력", 72f, palette.mine, bold = true))
            val at = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(saved.optLong("at")))
            card.addView(body("${saved.optString("lot")} · $at"))
        }
        card.addView(secondary("층·구역 입력") {
            setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_KEYPAD))
            render()
        }, spaced())
        root.addView(card, spaced())
    }

    private fun boardLot(): Lot? {
        val running = service ?: return null
        return running.state.lot ?: running.approachLot
    }

    private fun addBoard(root: LinearLayout, lot: Lot) {
        val running = service ?: return
        val card = card()
        card.addView(lotHeader(lot))
        val approaching = running.approachLot?.id == lot.id && running.state.lot == null
        val board = if (approaching) running.approachBoard else parkedBoard(lot)
        val heading = running.headingZone.takeIf { approaching }
        when {
            board == null && !boards.containsKey(lot.id) && !approaching -> card.addView(body("불러오는 중"))
            board == null -> card.addView(body("서버에 연결할 수 없어요. 저장한 내용은 연결되면 보낼게요."))
            else -> {
                card.addView(heading(BoardText.LIST_TITLE))
                val floors = BoardText.byFloor(board, heading)
                if (floors.isEmpty()) {
                    card.addView(body(BoardText.NO_NEWS))
                } else {
                    // Stopped in P on the way in, a tap on a zone tells the cars behind this car is going there.
                    val pick: ((String) -> Unit)? = if (approaching) { zone -> running.pickHeading(zone) } else null
                    card.addView(zoneList(floors, heading, recommended(board, heading), large = false, onPick = pick))
                    card.addView(caption(BoardText.CAVEAT))
                }
                if (heading != null) {
                    card.addView(secondary("${Guidance.spokenZone(heading)}에 자리 없어요") { running.missedHeading() }, spaced())
                }
                card.addView(caption(BoardText.sample(board)))
            }
        }
        root.addView(card, spaced())
    }

    /** Cached for a few seconds, so a re-render does not start a fetch that causes the next re-render. */
    private fun parkedBoard(lot: Lot): LotBoard? {
        val loadedAt = boardLoadedAt[lot.id] ?: 0L
        if (System.currentTimeMillis() - loadedAt > BOARD_CACHE_MILLIS) {
            boardLoadedAt[lot.id] = System.currentTimeMillis()
            service?.loadBoard(lot.id) { board ->
                boards[lot.id] = board
                render()
            }
        }
        return boards[lot.id]
    }

    private fun recommended(board: LotBoard?, heading: String?): String? =
        board?.let { BoardText.recommend(it, heading)?.zoneId }

    /**
     * The emulator's navigation has no destination a tester can easily type, so the route button stays
     * when the demo is off: two emulators and the server use it to head for the same real lot.
     */
    private fun addDemoControls(root: LinearLayout) {
        val card = card()
        card.addView(caption(if (settings.demoMode) "데모 순서" else "시연 도구"))
        card.addView(body(if (settings.demoMode) DEMO_STEPS else SERVER_STEPS))
        val buttons = wrapping()
        buttons.addView(secondary("1. 코엑스로 안내 시작") { service?.startDemoRoute() }, cell())
        if (settings.demoMode) {
            buttons.addView(secondary("2. 주차장 100m 앞 도착 (가상 위치)") { service?.simulateNear() }, cell())
            buttons.addView(secondary("3. 주차장 도착 (가상 위치)") { service?.simulateArrive() }, cell())
            buttons.addView(secondary("4. 10초 뒤 다른 차 출차") { service?.simulateDeparture() }, cell())
        }
        card.addView(buttons)
        val recent = service?.demo?.recentActivity().orEmpty()
        if (settings.demoMode && recent.isNotEmpty()) card.addView(caption(recent.joinToString("\n")))
        root.addView(card, spaced())
    }

    private fun addSettings(root: LinearLayout) {
        val card = card()
        card.addView(caption("설정"))
        val buttons = wrapping()
        buttons.addView(secondary(if (settings.voiceQuestions) "음성 질문 끄기" else "음성 질문 켜기") {
            settings.voiceQuestions = !settings.voiceQuestions
            render()
        }, cell())
        buttons.addView(secondary(if (settings.shareHeading) "가는 구역 알리기 끄기" else "가는 구역 알리기 켜기") {
            settings.shareHeading = !settings.shareHeading
            service?.headingSettingChanged()
            render()
        }, cell())
        buttons.addView(secondary(if (settings.demoMode) "데모 모드 끄기" else "데모 모드 켜기") {
            settings.demoMode = !settings.demoMode
            service?.backendChanged()
            render()
        }, cell())
        card.addView(buttons)
        addSharedLots(card)
        card.addView(secondary("데이터 삭제와 동의 철회") { withdraw() }, spaced())
        root.addView(card, spaced())
    }

    /**
     * Everything goes: the saved session, events not yet sent, notifications, settings. The service
     * stops, and does not start again at boot until consent is given anew.
     */
    private fun withdraw() {
        val running = service
        if (running != null) running.eraseEverything() else ParkingService.eraseFiles(this)
        settings.clearAll()
        stopService(Intent(this, ParkingService::class.java))
        boards.clear()
        boardLoadedAt.clear()
        setIntent(Intent(this, MainActivity::class.java))
        render()
    }

    private fun addSharedLots(root: LinearLayout) {
        val choices = settings.sharePreferences()
        if (choices.isEmpty()) return
        root.addView(caption("주차장별 공유"))
        choices.toSortedMap().forEach { (lotId, allowed) ->
            val name = service?.catalog?.byId(lotId)?.name ?: lotId
            root.addView(secondary("$name: ${if (allowed) "공유 중, 끄기" else "안 함, 켜기"}") {
                settings.rememberShare(lotId, !allowed)
                // Takes effect on the parking in progress, not only on the next one.
                service?.shareChanged(lotId, !allowed)
                render()
            }, spaced())
        }
    }

    /** The grid wraps, so a narrow split window still shows every key. */
    private fun showKeypad() {
        val root = page()
        addDemoNotice(root)
        val chosenFloor = floor
        root.addView(title(if (chosenFloor == null) "층을 고르세요" else "${Guidance.spokenZone(chosenFloor)} · 구역을 고르세요"))
        val grid = GridLayout(this).apply {
            columnCount = (resources.configuration.screenWidthDp / KEY_WIDTH_DP).coerceIn(2, MAX_KEY_COLUMNS)
        }
        (if (chosenFloor == null) FLOORS else ZONES).forEach { key ->
            grid.addView(secondary(key) {
                if (chosenFloor == null) {
                    floor = key
                    render()
                } else {
                    finishWith(ZoneParser.canonical(chosenFloor, key))
                }
            }, cell())
        }
        root.addView(grid, spaced())
        if (chosenFloor != null) root.addView(primary("층만 저장") { finishWith(chosenFloor) }, spaced())
        root.addView(secondary("취소") { closeKeypad() }, spaced())
        show(root)
    }

    private fun finishWith(zoneId: String) {
        startForegroundService(
            Intent(this, ParkingService::class.java)
                .setAction(ParkingService.ACTION_KEYPAD_RESULT)
                .putExtra(ParkingService.EXTRA_ZONE, zoneId),
        )
        closeKeypad()
    }

    private fun closeKeypad() {
        floor = null
        setIntent(Intent(this, MainActivity::class.java))
        render()
    }

    /** Opened from the list notification while stopped: which zone should the cars behind be told? */
    private fun showHeadingPicker() {
        val root = page()
        addDemoNotice(root)
        val running = service
        val board = running?.approachBoard
        val floors = board?.let { BoardText.byFloor(it, running.headingZone) }.orEmpty()
        root.addView(title("어느 구역으로 가세요?"))
        if (running == null || floors.isEmpty()) {
            root.addView(body(BoardText.NO_NEWS))
        } else {
            root.addView(body("고른 구역을 뒤따라오는 차에 알려요. 그 차들은 다른 구역을 권유받아요."))
            val heading = running.headingZone
            root.addView(zoneList(floors, heading, recommended(board, heading), large = false) { zone ->
                running.pickHeading(zone)
                closeKeypad()
            })
            root.addView(caption(BoardText.CAVEAT))
            if (heading != null) {
                root.addView(secondary("${Guidance.spokenZone(heading)}에 자리 없어요") {
                    running.missedHeading()
                    closeKeypad()
                }, spaced())
            }
            if (running.headingPublished) {
                root.addView(secondary("알림 취소") {
                    running.declineHeading()
                    closeKeypad()
                }, spaced())
            }
        }
        root.addView(secondary("닫기") { closeKeypad() }, spaced())
        show(root)
    }

    // --- The list: a floor on its band, zones beside it ---

    private fun zoneList(
        floors: List<FloorGroup>,
        mine: String?,
        recommended: String?,
        large: Boolean,
        onPick: ((String) -> Unit)?,
    ) = column().apply {
        floors.forEach { floor -> addView(floorSection(floor, mine, recommended, large, onPick), spaced()) }
    }

    private fun floorSection(
        floor: FloorGroup,
        mine: String?,
        recommended: String?,
        large: Boolean,
        onPick: ((String) -> Unit)?,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(palette.surface, dp(18), palette.line)
        // The band: what a driver looks for first on the way down the ramp.
        addView(TextView(this@MainActivity).apply {
            text = floor.badge
            textSize = if (large) 52f else 34f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = floor.label
            background = GradientDrawable().apply {
                setColor(palette.band)
                cornerRadii = floatArrayOf(dp(18f), dp(18f), 0f, 0f, 0f, 0f, dp(18f), dp(18f))
            }
        }, LinearLayout.LayoutParams(dp(if (large) 160 else 104), MATCH_PARENT))
        val rows = column()
        floor.zones.forEachIndexed { index, row ->
            if (index > 0) rows.addView(View(this@MainActivity).apply { setBackgroundColor(palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
            rows.addView(zoneRow(row, mine, recommended, large, onPick))
        }
        addView(rows, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun zoneRow(
        row: ZoneRow,
        mine: String?,
        recommended: String?,
        large: Boolean,
        onPick: ((String) -> Unit)?,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(GAP), dp(GAP / 2), dp(GAP), dp(GAP / 2))
        minimumHeight = dp(if (large) 148 else 92)
        val isMine = row.zoneId == mine
        val unclaimed = BoardText.unclaimed(row, mine)
        val ink = when {
            isMine -> palette.mine
            row.vacancyAges.isEmpty() -> palette.soon
            unclaimed == 0 -> palette.taken
            else -> palette.open
        }
        val mark = BoardText.zoneMark(row.zoneId)
        addView(TextView(this@MainActivity).apply {
            text = mark.ifEmpty { "전체" }
            textSize = if (large) 76f else 46f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(palette.text)
            minWidth = dp(if (large) 100 else 64)
        })
        if (mark.isNotEmpty()) addView(plain("구역", if (large) 36f else 26f, palette.secondary))
        // This car's own row already says so in its note; the chip is for the zone it is pointed to.
        if (!isMine && row.zoneId == recommended) addView(chip("추천", palette.open))
        val noteInk = if (isMine) palette.mine else palette.secondary
        addView(plain(BoardText.note(row, mine, service?.headingConfirmed == true).orEmpty(), if (large) 36f else 26f, noteInk, bold = isMine).apply {
            gravity = Gravity.END
            setPadding(dp(GAP / 2), 0, dp(GAP), 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = BoardText.count(row)
            textSize = if (large) 72f else 44f
            setTypeface(Typeface.DEFAULT_BOLD)
            fontFeatureSettings = "tnum"
            setTextColor(ink)
            gravity = Gravity.END
        })
        contentDescription = "${BoardText.zone(row)} ${BoardText.line(row, mine)}"
        if (onPick != null && row.vacancyAges.isNotEmpty() && !isMine) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onPick(row.zoneId) }
        }
    }

    private fun chip(label: String, color: Int) = TextView(this).apply {
        text = label
        textSize = 22f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(color)
        background = rounded(Color.TRANSPARENT, dp(20), color, dp(2))
        setPadding(dp(14), dp(4), dp(14), dp(4))
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(GAP / 2) }
    }

    private fun lotHeader(lot: Lot) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(plain(lot.name, 38f, palette.text, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(plain("약 ${"%,d".format(approximateCapacity(lot.capacity))}면", 30f, palette.secondary))
        setPadding(0, 0, 0, dp(GAP / 2))
    }

    // --- Building blocks ---

    private fun show(content: View) {
        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.background)
            addView(content, MATCH_PARENT, WRAP_CONTENT)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun page() = column().apply { setPadding(dp(PAGE), dp(PAGE), dp(PAGE), dp(PAGE)) }

    private fun card() = column().apply {
        background = rounded(palette.surface, dp(24), palette.line)
        setPadding(dp(PAGE), dp(GAP), dp(PAGE), dp(GAP))
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = dp(1)) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(strokeWidth, stroke)
    }

    private fun wrapping() = GridLayout(this).apply {
        columnCount = (resources.configuration.screenWidthDp / BUTTON_WIDTH_DP).coerceAtLeast(1)
    }

    private fun spaced() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(GAP / 2)
        bottomMargin = dp(GAP / 2)
    }

    private fun cell() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
    ).apply {
        width = 0
        setMargins(dp(GAP / 4), dp(GAP / 4), dp(GAP / 4), dp(GAP / 4))
    }

    private fun plain(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        breakByPhrase()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        plain(value, size, color, bold).apply { setPadding(0, 0, 0, dp(GAP / 2)) }

    /** Korean wraps at any character by default, which splits "1대" across lines in a narrow window. */
    private fun TextView.breakByPhrase() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        }
    }

    private fun title(value: String) = text(value, 44f, palette.text, bold = true)
    private fun heading(value: String) = text(value, 32f, palette.text, bold = true).apply { setPadding(0, dp(GAP / 2), 0, 0) }
    private fun body(value: String) = text(value, 30f, palette.secondary).apply {
        setLineSpacing(0f, 1.25f)
        // A line much longer than this is hard to follow on a wide display.
        maxWidth = dp(1180)
    }
    private fun caption(value: String) = text(value, 26f, palette.secondary)

    private fun primary(label: String, onClick: () -> Unit) = button(label, palette.band, Color.WHITE, null, onClick)

    private fun secondary(label: String, onClick: () -> Unit) = button(label, palette.surface, palette.text, palette.line, onClick)

    private fun button(label: String, fill: Int, ink: Int, stroke: Int?, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 30f
        minHeight = dp(KEY_HEIGHT)
        gravity = Gravity.CENTER
        setTextColor(ink)
        stateListAnimator = null
        background = rounded(fill, dp(18), stroke, dp(2))
        setOnClickListener { onClick() }
    }

    /**
     * [band] is the blue of a parking sign and belongs to the floor band and to this car's own choice.
     * The four state colours are checked for contrast against both surfaces.
     */
    private class Palette(
        val background: Int,
        val surface: Int,
        val line: Int,
        val text: Int,
        val secondary: Int,
        val band: Int,
        val mine: Int,
        val open: Int,
        val soon: Int,
        val taken: Int,
        val notice: Int,
    ) {
        companion object {
            val DAY = Palette(
                background = Color.rgb(238, 241, 244),
                surface = Color.WHITE,
                line = Color.rgb(208, 214, 221),
                text = Color.rgb(16, 22, 28),
                secondary = Color.rgb(78, 89, 101),
                band = Color.rgb(15, 76, 189),
                mine = Color.rgb(15, 76, 189),
                open = Color.rgb(6, 122, 67),
                soon = Color.rgb(163, 92, 0),
                taken = Color.rgb(112, 122, 133),
                notice = Color.rgb(84, 62, 160),
            )
            val NIGHT = Palette(
                background = Color.rgb(13, 17, 22),
                surface = Color.rgb(24, 30, 38),
                line = Color.rgb(48, 57, 68),
                text = Color.rgb(244, 246, 248),
                secondary = Color.rgb(165, 176, 188),
                band = Color.rgb(24, 92, 214),
                mine = Color.rgb(110, 170, 255),
                open = Color.rgb(74, 222, 128),
                soon = Color.rgb(255, 184, 64),
                taken = Color.rgb(132, 143, 155),
                notice = Color.rgb(84, 62, 160),
            )
        }
    }

    companion object {
        const val ACTION_KEYPAD = "app.parkrelay.KEYPAD"
        const val ACTION_PICK_HEADING = "app.parkrelay.PICK_HEADING_SCREEN"
        private const val STATE_FLOOR = "floor"
        private const val EXTRA_MODE = "mode"
        private const val MODE_SERVER = "server"
        private const val MODE_DEMO = "demo"
        private const val REQUEST_PERMISSIONS = 1
        private const val SERVICE_RETRY_MILLIS = 1_000L
        private const val BOARD_CACHE_MILLIS = 5_000L
        private const val DRIVING_ROWS = 4
        private const val PAGE = 32
        private const val GAP = 24
        private const val KEY_HEIGHT = 112
        private const val KEY_WIDTH_DP = 140
        private const val MAX_KEY_COLUMNS = 6
        private const val BUTTON_WIDTH_DP = 420

        private val FLOORS = listOf("B6", "B5", "B4", "B3", "B2", "B1", "1F", "2F", "3F", "4F", "5F", "6F")
        private val ZONES = listOf("A", "B", "C", "D", "E", "F", "G", "H", "1", "2", "3", "4", "5", "6", "7", "8")

        private val RUNTIME_PERMISSIONS = listOf(
            "pleos.car.permission.FUSED_LOCATION",
            "android.car.permission.CAR_SPEED",
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        private const val CONSENT_TEXT =
            "주차하면 층과 구역을 내 차 위치로 저장해요. 공유할 수 있는 주차장에서는 차가 나갈 때 " +
                "\"이 구역에서 1대가 나갔다\"는 사실을 다른 차에 알려요. 그 주차장으로 가는 차는 100m 앞에서 " +
                "층별 주차 가능 구역을 듣고, 어느 구역으로 가는지를 뒤따라오는 차에 알려요.\n\n" +
                "위치 판단은 차 안에서 끝나요. 이동 경로, 차대번호, 번호판은 서버로 보내지 않아요. " +
                "아파트처럼 주거 시설로 보이는 곳에서는 아무것도 보내지 않아요. " +
                "가는 구역 알리기는 설정에서 따로 끌 수 있어요.\n\n" +
                "언제든 설정에서 동의를 철회하면 이 차에 저장된 내용과 아직 보내지 않은 내용을 모두 지워요."

        private const val DEMO_STEPS =
            "1번을 누르고 내비로 돌아가세요. 안내가 시작되면 주차장 규모를 알려 드려요.\n" +
                "2번은 주차장 100m 앞에 도착한 상황을 만들어요. 층별 주차 가능 구역이 음성과 알림으로 오고, " +
                "앞차가 가는 구역을 피해 추천 구역으로 간다고 알려요.\n" +
                "3번을 누르면 주차장에 들어온 것으로 보고 가는 구역을 확정해요. 4번을 누르면 10초 뒤 다른 차가 나가요. 앱을 닫아도 음성과 알림이 와요.\n" +
                "Virtual Console에서 기어를 R, P 순으로 바꾸면 내 차 위치 저장을 물어요."

        private const val SERVER_STEPS =
            "서버에 연결된 상태예요. 차량 2대 장면은 PC의 시연 콘솔에서 순서대로 진행해요.\n" +
                "버튼 1은 내비 목적지를 코엑스 주차장으로 정해요. 주차장 100m 앞에 오면 " +
                "앱을 닫아도 층별 주차 가능 구역을 음성과 알림으로 알려 드려요."
    }
}
