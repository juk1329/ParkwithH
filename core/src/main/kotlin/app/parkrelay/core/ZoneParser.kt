package app.parkrelay.core

/** What a spoken answer to "where did you park?" means. */
sealed interface SpokenAnswer {
    data class Zone(val zoneId: String) : SpokenAnswer
    data object Yes : SpokenAnswer
    data object No : SpokenAnswer
    data object NotUnderstood : SpokenAnswer
}

/**
 * Turns recognized Korean speech such as "지하 2층 B구역" into a zone id such as "B2-B".
 *
 * Speech recognition returns free text, and spelling of letters and numbers varies, so this
 * normalizes those variants. It also backs the keypad, which produces the same zone id format.
 */
object ZoneParser {
    private val NUMBER_WORDS = mapOf(
        "일" to 1, "이" to 2, "삼" to 3, "사" to 4, "오" to 5, "육" to 6, "칠" to 7, "팔" to 8, "구" to 9,
    )

    // Longest spellings first, so "에이치" is not read as "에이" followed by leftovers.
    private val LETTER_WORDS = listOf(
        "에이치" to "H", "에이" to "A", "에프" to "F", "비" to "B", "씨" to "C", "디" to "D", "지" to "G",
    )

    private val FLOOR = Regex("""(지하|지상)?\s*(\d|[일이삼사오육칠팔구])\s*층""")
    private val SHORT_BASEMENT = Regex("""\b[Bb]\s*(\d)\b""")
    private val ZONE = Regex("""([A-Za-z]|에이치|에이|에프|비|씨|디|지|[가나다라마바사아자차카타파하]|\d{1,2})\s*(구역|존|블록|열)""")
    private val BARE_LETTER_AFTER_FLOOR = Regex("""층\s*([A-Za-z])\b""")

    private val YES = Regex("""^(네|예|응|어|맞아|맞아요|맞습니다|그래|그래요|좋아|좋아요|저장해|저장해줘)\b""")
    private val NO = Regex("""^(아니|아니요|아뇨|틀려|틀렸어|됐어|안\s*해)""")

    fun parse(speech: String): SpokenAnswer {
        val text = speech.trim()
        zoneId(text)?.let { return SpokenAnswer.Zone(it) }
        return when {
            YES.containsMatchIn(text) -> SpokenAnswer.Yes
            NO.containsMatchIn(text) -> SpokenAnswer.No
            else -> SpokenAnswer.NotUnderstood
        }
    }

    /** Builds the canonical id from keypad values. A null zone means the floor is all the driver knows. */
    fun canonical(floor: String, zone: String?): String =
        if (zone.isNullOrEmpty()) floor else "$floor-${zone.uppercase()}"

    private fun zoneId(text: String): String? {
        val floor = floorOf(text) ?: return null
        return canonical(floor, zoneOf(text))
    }

    private fun floorOf(text: String): String? {
        FLOOR.find(text)?.let { match ->
            val number = numberOf(match.groupValues[2])
            return if (match.groupValues[1] == "지하") "B$number" else "${number}F"
        }
        return SHORT_BASEMENT.find(text)?.let { "B${it.groupValues[1]}" }
    }

    private fun zoneOf(text: String): String? {
        ZONE.find(text)?.let { return letterOf(it.groupValues[1]) }
        BARE_LETTER_AFTER_FLOOR.find(text)?.let { return it.groupValues[1].uppercase() }
        val afterBasement = SHORT_BASEMENT.find(text)?.let { text.substring(it.range.last + 1) }
        return afterBasement?.let { Regex("""\b([A-Za-z])\b""").find(it)?.groupValues?.get(1)?.uppercase() }
    }

    private fun numberOf(token: String): Int = token.toIntOrNull() ?: NUMBER_WORDS.getValue(token)

    private fun letterOf(token: String): String =
        LETTER_WORDS.firstOrNull { it.first == token }?.second ?: token.uppercase()
}
