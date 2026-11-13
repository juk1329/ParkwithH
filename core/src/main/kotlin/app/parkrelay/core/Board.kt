package app.parkrelay.core

/**
 * One zone as reported by the server or demo mode. Ages are in milliseconds, newest first.
 * [claims] counts approaching cars that said they are heading to this zone.
 */
data class ZoneRow(
    val zoneId: String,
    val vacancyAges: List<Long>,
    val leavingSoon: Int,
    val parkedAppCars: Int,
    val claims: Int = 0,
)

data class LotBoard(val capacity: Int?, val parkedAppCars: Int, val zones: List<ZoneRow>)

/** The zones of one floor, in the order a floor directory lists them. */
data class FloorGroup(val badge: String, val label: String, val zones: List<ZoneRow>)

/**
 * What the board may say. The app only sees cars running it, so every count is "departures by app
 * cars in the last few minutes that no app car has parked into since". It is shown with its age and
 * with [CAVEAT], and is never presented as the lot's number of free spaces.
 */
object BoardText {
    const val NO_ZONE = "UNZONED"
    const val LIST_TITLE = "주차 가능 구역"
    const val CAVEAT = "앱 차량의 출차 기준이에요. 자리 보장은 아니에요."
    const val NO_NEWS = "아직 출차 소식이 없어요."
    private const val MINUTE = 60_000L
    private val FLOOR_ZONE = Regex("""^(B?)(\d)(F?)(?:-(.+))?$""")

    fun zone(row: ZoneRow): String =
        if (row.zoneId == NO_ZONE) "구역 미입력" else Guidance.spokenZone(row.zoneId) ?: row.zoneId

    fun status(row: ZoneRow): String {
        val newest = row.vacancyAges.firstOrNull()
        return when {
            newest != null -> {
                val others = row.vacancyAges.size - 1
                "${ago(newest)} 1대 출차" + if (others > 0) " 외 ${others}대" else ""
            }
            row.leavingSoon > 0 -> "곧 나갈 차 있음"
            else -> "소식 없음"
        }
    }

    fun hasNews(row: ZoneRow): Boolean = row.vacancyAges.isNotEmpty() || row.leavingSoon > 0

    /** Upper floors first, then deeper and deeper, as a floor directory reads. Unzoned reports go last. */
    fun inFloorOrder(rows: List<ZoneRow>): List<ZoneRow> =
        rows.sortedWith(compareBy({ floorRank(it.zoneId) }, { it.zoneId }))

    private fun floorRank(zoneId: String): Int {
        val match = FLOOR_ZONE.matchEntire(zoneId) ?: return Int.MAX_VALUE
        val number = match.groupValues[2].toInt()
        return if (match.groupValues[1] == "B") number else -number
    }

    /** States the sample size, so a handful of app cars is never read as the whole lot. */
    fun sample(board: LotBoard): String {
        val cars = "앱 차량 ${board.parkedAppCars}대"
        val capacity = board.capacity ?: return cars
        return "$cars / 등록 약 ${"%,d".format(approximateCapacity(capacity))}면"
    }

    /** The zones worth a driver's glance: a departure first, newest first, then cars about to leave. */
    fun forDriver(board: LotBoard, limit: Int = 3): List<ZoneRow> =
        board.zones
            .filter { it.zoneId != NO_ZONE && hasNews(it) }
            .sortedWith(compareBy({ it.vacancyAges.firstOrNull() ?: Long.MAX_VALUE }, { it.zoneId }))
            .take(limit)

    // --- The list of zones a driver can head for ---

    /** "B2" for a basement floor, "3F" above ground. */
    fun floorBadge(zoneId: String): String {
        val match = FLOOR_ZONE.matchEntire(zoneId) ?: return "?"
        val (basement, floor) = match.destructured
        return if (basement == "B") "B$floor" else "${floor}F"
    }

    fun floorLabel(zoneId: String): String {
        val match = FLOOR_ZONE.matchEntire(zoneId) ?: return "층 미입력"
        val (basement, floor) = match.destructured
        return if (basement == "B") "지하 ${floor}층" else "${floor}층"
    }

    /** "B구역", or "층 전체" when the driver gave only a floor. */
    fun zoneLabel(zoneId: String): String {
        val match = FLOOR_ZONE.matchEntire(zoneId) ?: return zoneId
        val zone = match.groupValues[4]
        return if (zone.isEmpty()) "층 전체" else "${zone}구역"
    }

    /** The letter or number painted on the pillar: "B" for "B2-B". Empty when only a floor is known. */
    fun zoneMark(zoneId: String): String = FLOOR_ZONE.matchEntire(zoneId)?.groupValues?.get(4).orEmpty()

    /**
     * Zones with a departure or a car about to leave, grouped by floor. Unzoned reports are left out.
     * With a [limit], as on the card a driver glances at while moving, the zones kept are this car's
     * own, then those with most departures nobody else is heading for, then the freshest.
     */
    fun byFloor(board: LotBoard, mine: String? = null, limit: Int = Int.MAX_VALUE): List<FloorGroup> {
        val listed = inFloorOrder(board.zones.filter { it.zoneId != NO_ZONE && hasNews(it) })
        val kept = if (listed.size <= limit) listed else {
            val worth = compareBy<ZoneRow>(
                { it.zoneId != mine },
                { -unclaimed(it, mine) },
                { it.vacancyAges.firstOrNull() ?: Long.MAX_VALUE },
            )
            inFloorOrder(listed.sortedWith(worth).take(limit))
        }
        return kept.groupBy { floorBadge(it.zoneId) }
            .map { (badge, zones) -> FloorGroup(badge, floorLabel(zones.first().zoneId), zones) }
    }

    /** Claims by other cars. The board is anonymous, so this car's own claim is taken out here. */
    fun othersClaims(row: ZoneRow, mine: String?): Int =
        (row.claims - if (mine == row.zoneId) 1 else 0).coerceAtLeast(0)

    /** Departures nobody else is heading for. */
    fun unclaimed(row: ZoneRow, mine: String?): Int =
        (row.vacancyAges.size - othersClaims(row, mine)).coerceAtLeast(0)

    /** "2곳" after confirmed departures, "곧 1곳" while a parked car has only shifted out of P. */
    fun count(row: ZoneRow): String = when {
        row.vacancyAges.isNotEmpty() -> "${row.vacancyAges.size}곳"
        row.leavingSoon > 0 -> "곧 ${row.leavingSoon}곳"
        else -> "소식 없음"
    }

    /** What qualifies the count: who is heading there, or else how fresh the newest departure is. */
    fun note(row: ZoneRow, mine: String?): String? {
        val newest = row.vacancyAges.firstOrNull() ?: return null
        val others = othersClaims(row, mine)
        val parts = buildList {
            if (mine == row.zoneId) add("내가 가는 중")
            if (others > 0) add("앞차 ${others}대 가는 중")
        }
        return if (parts.isEmpty()) ago(newest) else parts.joinToString(", ")
    }

    fun line(row: ZoneRow, mine: String?): String =
        "${zoneLabel(row.zoneId)} ${count(row)}" + (note(row, mine)?.let { " · $it" } ?: "")

    /** One line a floor, for a notification: "지하 2층  B구역 2곳 · 1분 전, C구역 곧 1곳". */
    fun listText(board: LotBoard, mine: String?): String? {
        val floors = byFloor(board, mine)
        if (floors.isEmpty()) return null
        return floors.joinToString("\n") { floor ->
            "${floor.label}  " + floor.zones.joinToString(", ") { line(it, mine) }
        }
    }

    /**
     * The zone to head for: most departures nobody else is heading to, then the freshest departure,
     * then floor order. Null when every departure already has a car on its way.
     */
    fun recommend(board: LotBoard, mine: String?): ZoneRow? =
        inFloorOrder(board.zones)
            .filter { it.zoneId != NO_ZONE && unclaimed(it, mine) > 0 }
            .sortedWith(compareBy({ -unclaimed(it, mine) }, { it.vacancyAges.first() }))
            .firstOrNull()

    /**
     * The list as speech, best zone first and at most [limit] zones so it stays one short utterance.
     * Null when there is nothing to say.
     */
    fun spokenList(board: LotBoard, mine: String?, limit: Int = 3): String? {
        val rows = inFloorOrder(board.zones).filter { it.zoneId != NO_ZONE }
        val open = rows.filter { unclaimed(it, mine) > 0 }
            .sortedWith(compareBy({ -unclaimed(it, mine) }, { it.vacancyAges.first() }))
            .take(limit)
        val taken = rows.filter { it.vacancyAges.isNotEmpty() && unclaimed(it, mine) == 0 }
        val soon = rows.filter { it.vacancyAges.isEmpty() && it.leavingSoon > 0 }
        val sentences = buildList {
            if (open.isNotEmpty()) add(open.joinToString(", ") { "${zone(it)} ${unclaimed(it, mine)}곳" } + ".")
            val room = limit - open.size
            if (room > 0 && taken.isNotEmpty()) {
                add(taken.take(room).joinToString(", ") { zone(it) } + "은 앞차가 가는 중이에요.")
            }
            if (open.isEmpty() && taken.isEmpty() && soon.isNotEmpty()) {
                add(soon.take(limit).joinToString(", ") { zone(it) } + "에서 곧 자리가 날 것 같아요.")
            }
        }
        return if (sentences.isEmpty()) null else sentences.joinToString(" ")
    }

    fun ago(millis: Long): String {
        val minutes = millis / MINUTE
        return if (minutes < 1) "방금" else "${minutes}분 전"
    }
}
