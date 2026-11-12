package app.parkrelay.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ZoneParserTest {
    private fun zone(id: String) = SpokenAnswer.Zone(id)

    @Test
    fun `floor and zone in common spoken forms`() {
        val cases = mapOf(
            "지하 2층 B구역" to "B2-B",
            "지하2층 B 구역" to "B2-B",
            "지하 이층 비 구역" to "B2-B",
            "지하 삼층 에이 구역이요" to "B3-A",
            "지하 1층 씨구역에 댔어" to "B1-C",
            "B2 B" to "B2-B",
            "b3 c" to "B3-C",
            "지하 2층 가구역" to "B2-가",
            "지하 2층 12구역" to "B2-12",
            "지상 3층 디 구역" to "3F-D",
            "3층 A구역" to "3F-A",
            "지하 2층 B" to "B2-B",
            "지하 에이치" to null,
        )
        for ((speech, expected) in cases) {
            val want = expected?.let { zone(it) } ?: SpokenAnswer.NotUnderstood
            assertEquals(want, ZoneParser.parse(speech), speech)
        }
    }

    /** Recorded from the on-device engine on the emulator, for one spoken "지하 2층 B구역이요". */
    @Test
    fun `what the speech engine really returned`() {
        val heard = listOf(
            "지하 이층 비구역이요",
            "지하 2층 비구역이요",
            "지하 이층 b구역이요",
            "지하 2층 b 구역이요",
            "지하 이층 b 구역이요",
            "지하 이층 비 구역이요",
        )
        for (speech in heard) assertEquals(zone("B2-B"), ZoneParser.parse(speech), speech)
    }

    @Test
    fun `floor alone is still useful`() {
        assertEquals(zone("B2"), ZoneParser.parse("지하 이층이요"))
        assertEquals(zone("1F"), ZoneParser.parse("일층"))
        assertEquals(zone("B4"), ZoneParser.parse("B4"))
    }

    @Test
    fun `yes and no`() {
        for (speech in listOf("네", "네 맞아요", "응", "맞아", "그래", "저장해줘")) {
            assertEquals(SpokenAnswer.Yes, ZoneParser.parse(speech), speech)
        }
        for (speech in listOf("아니", "아니요 다른 데요", "됐어", "안 해")) {
            assertEquals(SpokenAnswer.No, ZoneParser.parse(speech), speech)
        }
    }

    @Test
    fun `unrelated speech is not mistaken for a zone`() {
        for (speech in listOf("", "음악 틀어줘", "오늘 날씨 어때")) {
            assertEquals(SpokenAnswer.NotUnderstood, ZoneParser.parse(speech), speech)
        }
    }

    @Test
    fun `keypad values use the same format`() {
        assertEquals("B2-B", ZoneParser.canonical("B2", "b"))
        assertEquals("B2", ZoneParser.canonical("B2", null))
        assertEquals("1F", ZoneParser.canonical("1F", ""))
    }
}
