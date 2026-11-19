package dev.bit.media

data class Chapter(val title: String, val start: Int, val end: Int)

/** One stream variant reported by yt-dlp. [bytes] is reported or derived from bitrate × duration. */
data class SourceFormat(val height: Int?, val video: Boolean, val audio: Boolean, val bytes: Long?)

internal object MediaMeta {
    /** Distinct video heights, best first. Tiny storyboard-like variants are left out. */
    fun qualities(formats: List<SourceFormat>): List<Int> =
        formats.filter { it.video }.mapNotNull { it.height }.filter { it >= 144 }.distinct().sortedDescending()

    /**
     * A rough download size for what `run()` is likely to select, scaled to the requested
     * share of the item. Null when the provider reports neither sizes nor bitrates.
     */
    fun estimate(formats: List<SourceFormat>, mode: Mode, height: Int?, fraction: Double): Long? {
        fun fits(f: SourceFormat) = height == null || (f.height ?: 0) <= height
        // yt-dlp prefers resolution first; among equal heights take the largest variant.
        fun pick(candidates: List<SourceFormat>): Long? {
            val top = candidates.maxOfOrNull { it.height ?: 0 } ?: return null
            return candidates.filter { (it.height ?: 0) == top }.mapNotNull { it.bytes }.maxOrNull()
        }
        val videoOnly = formats.filter { it.video && !it.audio && fits(it) }
        val combined = formats.filter { it.video && it.audio && fits(it) }
        val audio = formats.filter { it.audio && !it.video }.mapNotNull { it.bytes }.maxOrNull()
        val bytes = when (mode) {
            Mode.AUDIO -> audio ?: pick(combined)
            Mode.VIDEO -> pick(videoOnly) ?: pick(combined)
            Mode.BOTH -> {
                val video = pick(videoOnly)
                if (video != null && audio != null) video + audio else pick(combined)
            }
        } ?: return null
        return (bytes * fraction.coerceIn(0.0, 1.0)).toLong().takeIf { it > 0 }
    }
}

/** Reads a start offset such as `t=90`, `t=1m30s` or `#t=1:30` from a shared link. */
internal object LinkTime {
    private val units = Regex("""(?:(\d{1,4})h)?(?:(\d{1,5})m)?(?:(\d{1,7})s?)?""")
    private val clock = Regex("""\d{1,5}(?::[0-5]?\d){1,2}""")

    fun start(url: String): Int? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        for (part in listOfNotNull(uri.rawQuery, uri.rawFragment).flatMap { it.split('&') }) {
            val key = part.substringBefore('=', "")
            if (key == "t" || key == "start") return parse(part.substringAfter('='))?.takeIf { it > 0 }
        }
        return null
    }

    fun parse(value: String): Int? {
        val text = value.trim().lowercase()
        if (text.isEmpty()) return null
        if (clock.matches(text)) return text.split(':').fold(0L) { total, p -> total * 60 + p.toLong() }.toInt()
        val match = units.matchEntire(text) ?: return null
        val (h, m, s) = match.destructured
        val total = (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return total.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
}
