package app.parkrelay.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardTest {
    private fun row(zone: String, ages: List<Long> = emptyList(), leaving: Int = 0, parked: Int = 0) =
        ZoneRow(zone, ages, leaving, parked)

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
}
