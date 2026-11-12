package app.parkrelay.core

/** One zone as reported by the server or demo mode. Ages are in milliseconds, newest first. */
data class ZoneRow(
    val zoneId: String,
    val vacancyAges: List<Long>,
    val leavingSoon: Int,
    val parkedAppCars: Int,
)

data class LotBoard(val capacity: Int?, val parkedAppCars: Int, val zones: List<ZoneRow>)

/**
 * What the board may say. The app only sees cars running it, so it reports what those cars did and
 * how long ago. It never states a number of free spaces.
 */
object BoardText {
    const val NO_ZONE = "UNZONED"
    private const val MINUTE = 60_000L
    private val FLOOR = Regex("""^(B?)(\d)""")

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
        val match = FLOOR.find(zoneId) ?: return Int.MAX_VALUE
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

    private fun ago(millis: Long): String {
        val minutes = millis / MINUTE
        return if (minutes < 1) "방금" else "${minutes}분 전"
    }
}
