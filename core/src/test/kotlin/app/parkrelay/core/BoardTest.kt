package app.parkrelay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardTest {
    private fun row(
        zone: String,
        ages: List<Long> = emptyList(),
        leaving: Int = 0,
        parked: Int = 0,
        claims: Int = 0,
    ) = ZoneRow(zone, ages, leaving, parked, claims)

    private fun board(vararg rows: ZoneRow) = LotBoard(2123, 5, rows.toList())

    @Test
    fun `a departure reads as a fact with its age, not as a free space`() {
        assertEquals("방금 1대 출차", BoardText.status(row("B2-B", listOf(20_000))))
        assertEquals("3분 전 1대 출차", BoardText.status(row("B2-B", listOf(190_000))))
        assertEquals("방금 1대 출차 외 2대", BoardText.status(row("B2-B", listOf(5_000, 60_000, 120_000))))
    }

    @Test
    fun `a departure outranks a car about to leave`() {
        assertEquals("방금 1대 출차", BoardText.status(row("B2-B", listOf(5_000), leaving = 1)))
        assertEquals("곧 나갈 차 있음", BoardText.status(row("B2-B", leaving = 1)))
        assertEquals("소식 없음", BoardText.status(row("B2-B", parked = 4)))
    }

    @Test
    fun `the sample size is always stated`() {
        assertEquals("앱 차량 3대 / 등록 약 2,100면", BoardText.sample(LotBoard(2123, 3, emptyList())))
        assertEquals("앱 차량 3대", BoardText.sample(LotBoard(null, 3, emptyList())))
    }

    @Test
    fun `a driver sees the freshest departures first and nothing without news`() {
        val board = LotBoard(
            924, 5,
            listOf(
                row("B1-A", parked = 3),
                row("B1-C", leaving = 1),
                row("B2-A", listOf(120_000)),
                row("B2-B", listOf(10_000)),
                row(BoardText.NO_ZONE, listOf(1_000)),
            ),
        )
        assertEquals(listOf("B2-B", "B2-A", "B1-C"), BoardText.forDriver(board).map { it.zoneId })
        assertEquals(listOf("B2-B"), BoardText.forDriver(board, limit = 1).map { it.zoneId })
    }

    @Test
    fun `zones read like a floor directory`() {
        val rows = listOf("B1-C", BoardText.NO_ZONE, "B3-D", "3F-A", "B1-A", "1F-B").map { row(it) }
        assertEquals(
            listOf("3F-A", "1F-B", "B1-A", "B1-C", "B3-D", BoardText.NO_ZONE),
            BoardText.inFloorOrder(rows).map { it.zoneId },
        )
    }

    @Test
    fun `an unzoned report has a plain label`() {
        assertEquals("구역 미입력", BoardText.zone(row(BoardText.NO_ZONE)))
        assertEquals("지하 2층 B구역", BoardText.zone(row("B2-B")))
    }

    @Test
    fun `zones group by floor with a count and its age`() {
        val board = board(
            row("B2-C", leaving = 1),
            row("B1-A", listOf(250_000)),
            row("B2-B", listOf(70_000, 200_000)),
            row("B1-D", parked = 2),
            row(BoardText.NO_ZONE, listOf(1_000)),
        )
        val floors = BoardText.byFloor(board)
        assertEquals(listOf("B1", "B2"), floors.map { it.badge })
        assertEquals(listOf("지하 1층", "지하 2층"), floors.map { it.label })
        assertEquals(listOf("B2-B", "B2-C"), floors[1].zones.map { it.zoneId })
        assertEquals(
            "지하 1층  A구역 1곳 · 4분 전\n지하 2층  B구역 2곳 · 1분 전, C구역 곧 1곳",
            BoardText.listText(board, mine = null),
        )
    }

    @Test
    fun `a board without news has no list`() {
        assertNull(BoardText.listText(board(row("B1-A", parked = 3)), mine = null))
        assertNull(BoardText.spokenList(board(row("B1-A", parked = 3)), mine = null))
        assertNull(BoardText.recommend(board(row("B1-A", parked = 3)), mine = null))
    }

    @Test
    fun `a departure another car is heading for is shown but not recommended`() {
        val board = board(
            row("B1-A", listOf(10_000), claims = 1),
            row("B2-B", listOf(120_000)),
        )
        assertEquals("A구역 1곳 · 앞차 1대 가는 중", BoardText.line(board.zones[0], mine = null))
        assertEquals("B2-B", BoardText.recommend(board, mine = null)?.zoneId)
    }

    @Test
    fun `my own claim does not hide my zone from me`() {
        val board = board(row("B2-B", listOf(30_000), claims = 1), row("B1-A", listOf(300_000)))
        assertEquals("B2-B", BoardText.recommend(board, mine = "B2-B")?.zoneId)
        assertEquals("B구역 1곳 · 내가 가는 중", BoardText.line(board.zones[0], mine = "B2-B"))
        // To any other car the same row reads as taken.
        assertEquals("B1-A", BoardText.recommend(board, mine = null)?.zoneId)
    }

    @Test
    fun `the zone with most unclaimed departures wins and ties go to the freshest`() {
        val most = board(row("B1-A", listOf(5_000)), row("B2-B", listOf(60_000, 120_000)))
        assertEquals("B2-B", BoardText.recommend(most, mine = null)?.zoneId)
        val tie = board(row("B1-A", listOf(200_000)), row("B2-B", listOf(20_000)))
        assertEquals("B2-B", BoardText.recommend(tie, mine = null)?.zoneId)
        val claimed = board(row("B1-A", listOf(5_000)), row("B2-B", listOf(60_000, 120_000), claims = 2))
        assertEquals("B1-A", BoardText.recommend(claimed, mine = null)?.zoneId)
    }

    @Test
    fun `the spoken list names at most three zones, best first`() {
        val board = board(
            row("B1-A", listOf(250_000)),
            row("B2-B", listOf(70_000, 200_000)),
            row("B3-A", listOf(10_000)),
            row("B3-D", listOf(400_000)),
            row("B4-A", leaving = 1),
        )
        assertEquals(
            "지하 2층 B구역 2곳, 지하 3층 A구역 1곳, 지하 1층 A구역 1곳.",
            BoardText.spokenList(board, mine = null),
        )
    }

    @Test
    fun `the spoken list says when a zone already has a car on its way`() {
        val board = board(row("B1-A", listOf(10_000), claims = 1), row("B2-B", listOf(120_000)))
        assertEquals(
            "지하 2층 B구역 1곳. 지하 1층 A구역은 앞차가 가는 중이에요.",
            BoardText.spokenList(board, mine = null),
        )
    }

    @Test
    fun `with only cars about to leave the list says so`() {
        assertEquals(
            "지하 2층 C구역에서 곧 자리가 날 것 같아요.",
            BoardText.spokenList(board(row("B2-C", leaving = 1)), mine = null),
        )
    }

    @Test
    fun `a floor given without a zone still has a place in the list`() {
        assertEquals("B2", BoardText.floorBadge("B2"))
        assertEquals("층 전체", BoardText.zoneLabel("B2"))
        assertEquals("3F", BoardText.floorBadge("3F-A"))
        assertEquals("A", BoardText.zoneMark("3F-A"))
    }

    @Test
    fun `a short list keeps my zone and the best of the rest, still in floor order`() {
        val board = board(
            row("B1-A", listOf(500_000)),
            row("B1-C", leaving = 1),
            row("B2-B", listOf(70_000, 200_000)),
            row("B3-A", listOf(10_000)),
            row("B4-D", listOf(300_000), claims = 1),
        )
        val floors = BoardText.byFloor(board, mine = "B4-D", limit = 3)
        assertEquals(listOf("B2-B", "B3-A", "B4-D"), floors.flatMap { it.zones }.map { it.zoneId })
        assertEquals(5, BoardText.byFloor(board).sumOf { it.zones.size })
    }
}
