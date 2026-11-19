package dev.bit.media

import java.util.Locale

internal sealed interface FormRange {
    data class Valid(val start: Int, val end: Int?) : FormRange
    data class Invalid(val message: String) : FormRange
}

internal object FormTime {
    fun format(seconds: Int): String = if (seconds >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

    private val pattern = Regex("""[0-9]+(?::[0-5][0-9]){1,2}""")

    private fun seconds(input: String): Int? {
        val text = input.trim()
        if (!pattern.matches(text)) return null
        var total = 0L
        for (part in text.split(':')) {
            total = total * 60 + (part.toIntOrNull() ?: return null)
            if (total > Int.MAX_VALUE) return null
        }
        return total.toInt()
    }

    fun range(from: String, until: String, duration: Int?): FormRange {
        val start = seconds(from) ?: return FormRange.Invalid("Enter From as m:ss or h:mm:ss.")
        val end = if (until.isBlank()) null else seconds(until)
            ?: return FormRange.Invalid("Enter To as m:ss or h:mm:ss.")
        return when {
            end != null && start >= end -> FormRange.Invalid("To must be later than From.")
            duration != null && start >= duration -> FormRange.Invalid("From must be before the end of the item.")
            duration != null && end != null && end > duration -> FormRange.Invalid("To exceeds the item duration.")
            // A prefilled end is not a request to trim at the metadata duration.
            else -> FormRange.Valid(start, end?.takeUnless { it == duration })
        }
    }
}
