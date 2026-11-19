package dev.bit.media

internal data class ExportName(val base: String, val suffix: String) {
    companion object {
        fun split(displayName: String): ExportName {
            val dot = displayName.lastIndexOf('.')
            return if (dot in 1 until displayName.lastIndex)
                ExportName(displayName.substring(0, dot), displayName.substring(dot))
            else ExportName(displayName, "")
        }

        /**
         * The base name of a new export. A custom [name] is used as typed (minus characters
         * Android rejects) and replaces the automatic range label; a source title is shortened.
         */
        fun base(title: String, name: String?, range: String): String {
            val clean = { text: String -> text.replace(Regex("""[\p{Cntrl}/\\:*?"<>|]"""), "_").trim(' ', '.') }
            if (name != null) return clean(name).ifBlank { "Media" }
            return clean(title).take(55).trimEnd { it.isHighSurrogate() }.ifBlank { "Media" } + range
        }
    }

    fun problem(input: String): String? = when {
        input.isBlank() -> "Enter a file name."
        input.startsWith('.') || input.endsWith('.') || input != input.trim() ->
            "Avoid a leading dot, trailing dot, or spaces at either end."
        input.any { it.isISOControl() || it in "\\/:*?\"<>|" } -> "Remove special characters such as / or :."
        (input + suffix).toByteArray().size > 255 -> "Use a shorter file name."
        else -> null
    }

    /** Caller excludes the current row; reserve space for the number without splitting a code point. */
    fun availableBase(input: String, siblings: Set<String>): String? {
        val taken = siblings.toSortedSet(String.CASE_INSENSITIVE_ORDER)
        if (input + suffix !in taken) return input
        var stem = input
        var number = 1
        while (true) {
            val tail = " ($number)"
            if ((tail + suffix).toByteArray().size >= 255) return null
            while (stem.isNotEmpty() && (stem + tail + suffix).toByteArray().size > 255) {
                stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
            }
            if (stem.isEmpty()) return null
            val candidate = stem + tail
            if (candidate + suffix !in taken) return candidate
            number++
        }
    }
}
