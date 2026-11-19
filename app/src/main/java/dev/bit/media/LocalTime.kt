package dev.bit.media

import java.util.Locale

internal object LocalTime {
    private val pattern = Regex("""([0-9]+(?::[0-5][0-9]){0,2})(?:\.([0-9]{1,3}))?""")

    fun parse(input: String): Long? {
        val match = pattern.matchEntire(input.trim()) ?: return null
        var seconds = 0L
        for (part in match.groupValues[1].split(':')) {
            val n = part.toLongOrNull() ?: return null
            if (seconds > (Long.MAX_VALUE - n) / 60) return null
            seconds = seconds * 60 + n
        }
        val fraction = match.groupValues[2].padEnd(3, '0').toLong()
        return if (seconds <= (Long.MAX_VALUE - fraction) / 1000) seconds * 1000 + fraction else null
    }

    fun format(ms: Long): String = String.format(
        Locale.ROOT, "%d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000
    )
}
