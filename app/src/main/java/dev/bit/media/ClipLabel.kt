package dev.bit.media

import java.util.Locale

internal fun clipLabel(startMs: Long, endMs: Long?): String {
    fun stamp(ms: Long): String {
        val base = String.format(Locale.ROOT, "%02dm%02ds", ms / 60_000, ms / 1000 % 60)
        return if (ms % 1000 == 0L) base else base.dropLast(1) + String.format(Locale.ROOT, ".%03ds", ms % 1000)
    }
    return " · ${stamp(startMs)}–${endMs?.let(::stamp) ?: "end"}"
}
