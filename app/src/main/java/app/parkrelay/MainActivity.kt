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
 * the car's location, the lot's board, the keypad and the settings.
 *
 * Every layout here works at any window width: rows wrap, and the page scrolls.
 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var palette: Palette
    private val handler = Handler(Looper.getMainLooper())
    private var floor: String? = null
    private var askedPermissions = false

    private val service get() = ParkingService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        palette = if (night) Palette.NIGHT else Palette.DAY
        window.decorView.setBackgroundColor(palette.background)
        applyLaunchMode(intent)
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
        if (settings.consent) startWhenPermitted()
        render()
        follow()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        service?.onUiChange = null
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
            !settings.consent -> showConsent()
            service?.isDriving == true -> showDrivingCard()
            intent?.action == ACTION_KEYPAD -> showKeypad()
            else -> showHome()
        }
    }

    private fun showConsent() {
        val root = page()
        root.addView(title("내 차 위치를 저장하고, 나갈 때 다른 차에 알려요"))
        root.addView(body(CONSENT_TEXT))
        root.addView(primary("동의하고 시작") {
            settings.consent = true
            startWhenPermitted()
            render()
            follow()
        })
        show(root)
    }

    /** Glanceable and untouchable: no buttons, no scrolling needed, nothing that moves. */
    private fun showDrivingCard() {
        val root = page()
        addDemoNotice(root)
        val lot = service?.approachLot
        if (lot == null) {
            root.addView(title("안내할 주차장이 없어요"))
            root.addView(body("내비 목적지가 주차장 근처면 소식을 음성으로 알려 드려요."))
            show(root)
            return
        }
        root.addView(body("${lot.name} · 약 ${"%,d".format(approximateCapacity(lot.capacity))}면"))
        root.addView(huge(service?.lastGuidance ?: "아직 들어온 구역 소식이 없어요."))
        val tiles = column()
        root.addView(tiles)
        service?.loadBoard(lot.id) { board -> board?.let { fillDriverTiles(tiles, it) } }
        show(root)
    }

    private fun fillDriverTiles(container: LinearLayout, board: LotBoard) {
        container.removeAllViews()
        val tiles = wrapping()
        BoardText.forDriver(board).forEach { row -> tiles.addView(zoneRow(row, large = true), cell()) }
        container.addView(tiles)
    }

    private fun showHome() {
        val root = page()
        addDemoNotice(root)
        addCarLocation(root)
        root.addView(primary("층·구역 입력") {
            setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_KEYPAD))
            render()
        })
        boardLot()?.let { addBoard(root, it) }
        addDemoControls(root)
        addSettings(root)
        show(root)
    }

    private fun addDemoNotice(root: LinearLayout) {
        if (!settings.demoMode) return
        root.addView(TextView(this).apply {
            text = "데모 모드 · 다른 차량과 구역 이름은 예시이고, 서버로 아무것도 보내지 않아요"
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(palette.notice)
            setPadding(GAP, GAP / 2, GAP, GAP / 2)
            breakByPhrase()
        }, spaced())
    }

    private fun addCarLocation(root: LinearLayout) {
        root.addView(heading("내 차 위치"))
        val saved = settings.carLocation()
        if (saved == null) {
            root.addView(body("아직 저장된 위치가 없어요. 주차하면 알림으로 물어볼게요."))
            return
        }
        val zone = saved.optString("zone").takeIf { it.isNotEmpty() && it != "null" }
        root.addView(huge(Guidance.spokenZone(zone) ?: "구역 미입력"))
        val at = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(saved.optLong("at")))
        root.addView(body("${saved.optString("lot")} · $at"))
    }

    private fun boardLot(): Lot? {
        val running = service ?: return null
        return running.state.lot ?: running.approachLot
    }

    private fun addBoard(root: LinearLayout, lot: Lot) {
        root.addView(heading("${lot.name} 현황"))
        val rows = column()
        rows.addView(body("불러오는 중"))
        root.addView(rows)
        service?.loadBoard(lot.id) { board -> fillBoard(rows, board) }
    }

    private fun fillBoard(rows: LinearLayout, board: LotBoard?) {
        rows.removeAllViews()
        if (board == null) {
            rows.addView(body("서버에 연결할 수 없어요. 저장한 내용은 연결되면 보낼게요."))
            return
        }
        if (board.zones.isEmpty()) rows.addView(body("아직 이 주차장에 공유된 소식이 없어요."))
        val tiles = wrapping()
        BoardText.inFloorOrder(board.zones).forEach { tiles.addView(zoneRow(it, large = false), cell()) }
        rows.addView(tiles)
        rows.addView(body(BoardText.sample(board)))
    }

    /**
     * The emulator's navigation has no destination a tester can easily type, so the route button stays
     * when the demo is off: two emulators and the server use it to head for the same real lot.
     */
    private fun addDemoControls(root: LinearLayout) {
        root.addView(heading(if (settings.demoMode) "데모 순서" else "시연 도구"))
        root.addView(body(if (settings.demoMode) DEMO_STEPS else SERVER_STEPS))
        val buttons = wrapping()
        buttons.addView(secondary("1. 코엑스로 안내 시작") { service?.startDemoRoute() }, cell())
        if (settings.demoMode) {
            buttons.addView(secondary("2. 10초 뒤 다른 차 출차") { service?.simulateDeparture() }, cell())
        }
        root.addView(buttons)
        val recent = service?.demo?.recentActivity().orEmpty()
        if (recent.isNotEmpty()) root.addView(body(recent.joinToString("\n")))
    }

    private fun addSettings(root: LinearLayout) {
        root.addView(heading("설정"))
        val buttons = wrapping()
        buttons.addView(secondary(if (settings.voiceQuestions) "음성 질문 끄기" else "음성 질문 켜기") {
            settings.voiceQuestions = !settings.voiceQuestions
            render()
        }, cell())
        buttons.addView(secondary(if (settings.demoMode) "데모 모드 끄기" else "데모 모드 켜기") {
            settings.demoMode = !settings.demoMode
            service?.backendChanged()
            render()
        }, cell())
        root.addView(buttons)
        addSharedLots(root)
        root.addView(secondary("데이터 삭제와 동의 철회") {
            settings.clearAll()
            stopService(Intent(this, ParkingService::class.java))
            setIntent(Intent(this, MainActivity::class.java))
            render()
        }, spaced())
    }

    private fun addSharedLots(root: LinearLayout) {
        val choices = settings.sharePreferences()
        if (choices.isEmpty()) return
        root.addView(body("주차장별 공유"))
        choices.toSortedMap().forEach { (lotId, allowed) ->
            val name = service?.catalog?.byId(lotId)?.name ?: lotId
            root.addView(secondary("$name: ${if (allowed) "공유 중, 끄기" else "안 함, 켜기"}") {
                settings.rememberShare(lotId, !allowed)
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

    private fun show(content: View) {
        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.background)
            addView(content, MATCH_PARENT, WRAP_CONTENT)
        })
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun page() = column().apply { setPadding(PAGE, PAGE, PAGE, PAGE) }

    private fun wrapping() = GridLayout(this).apply {
        columnCount = (resources.configuration.screenWidthDp / BUTTON_WIDTH_DP).coerceAtLeast(1)
    }

    private fun spaced() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = GAP / 2
        bottomMargin = GAP / 2
    }

    private fun cell() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
        GridLayout.spec(GridLayout.UNDEFINED, 1f),
    ).apply {
        width = 0
        setMargins(GAP / 4, GAP / 4, GAP / 4, GAP / 4)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, GAP / 2)
        breakByPhrase()
    }

    /** Korean wraps at any character by default, which splits "1대" across lines in a narrow window. */
    private fun TextView.breakByPhrase() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        }
    }

    private fun title(value: String) = text(value, 40f, palette.text, bold = true)
    private fun heading(value: String) = text(value, 34f, palette.text, bold = true).apply { setPadding(0, GAP, 0, GAP / 2) }
    private fun huge(value: String) = text(value, 72f, palette.accent, bold = true)
    private fun body(value: String) = text(value, 28f, palette.secondary)

    private fun zoneRow(row: ZoneRow, large: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, GAP / 2, 0, GAP / 2)
        addView(text(BoardText.zone(row), if (large) 52f else 34f, palette.text, bold = true))
        val statusColor = if (BoardText.hasNews(row)) palette.accent else palette.secondary
        addView(text(BoardText.status(row), if (large) 40f else 28f, statusColor))
    }

    private fun primary(label: String, onClick: () -> Unit) = button(label, palette.text, palette.background, onClick)

    private fun secondary(label: String, onClick: () -> Unit) = button(label, palette.background, palette.text, onClick)

    private fun button(label: String, fill: Int, ink: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 30f
        minHeight = KEY_HEIGHT
        gravity = Gravity.CENTER
        setTextColor(ink)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(fill)
            setStroke(BORDER, palette.text)
        }
        setOnClickListener { onClick() }
    }

    private class Palette(
        val background: Int,
        val text: Int,
        val secondary: Int,
        val accent: Int,
        val notice: Int,
    ) {
        companion object {
            val DAY = Palette(Color.WHITE, Color.rgb(17, 17, 17), Color.rgb(80, 80, 80), Color.rgb(0, 80, 200), Color.rgb(90, 60, 170))
            val NIGHT = Palette(Color.rgb(18, 18, 18), Color.WHITE, Color.rgb(200, 200, 200), Color.rgb(110, 180, 255), Color.rgb(90, 60, 170))
        }
    }

    companion object {
        const val ACTION_KEYPAD = "app.parkrelay.KEYPAD"
        private const val EXTRA_MODE = "mode"
        private const val MODE_SERVER = "server"
        private const val MODE_DEMO = "demo"
        private const val REQUEST_PERMISSIONS = 1
        private const val SERVICE_RETRY_MILLIS = 1_000L
        private const val PAGE = 32
        private const val GAP = 24
        private const val BORDER = 2
        private const val KEY_HEIGHT = 120
        private const val KEY_WIDTH_DP = 140
        private const val MAX_KEY_COLUMNS = 6
        private const val BUTTON_WIDTH_DP = 360

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
                "\"이 구역에서 1대가 나갔다\"는 사실을 다른 차에 알려요.\n\n" +
                "위치 판단은 차 안에서 끝나요. 이동 경로, 차대번호, 번호판은 서버로 보내지 않아요. " +
                "아파트처럼 주거 시설로 보이는 곳에서는 아무것도 보내지 않아요.\n\n" +
                "언제든 설정에서 동의를 철회하고 데이터를 지울 수 있어요."

        private const val DEMO_STEPS =
            "버튼 1을 누르고 내비로 돌아가세요. 안내가 시작되면 규모 안내가 들려요.\n" +
                "버튼 2를 누르면 10초 뒤 다른 차가 나가요. 앱을 닫아도 음성과 알림이 와요.\n" +
                "Virtual Console에서 기어를 R, P 순으로 바꾸면 내 차 위치 저장을 물어요."

        private const val SERVER_STEPS =
            "서버에 연결된 상태예요. 차량 2대 장면은 PC의 시연 콘솔에서 순서대로 진행해요.\n" +
                "버튼 1은 내비 목적지를 코엑스 주차장으로 정해요. 다른 차가 이 주차장에서 나가면 " +
                "앱을 닫아도 음성과 알림으로 알려 드려요."
    }
}
