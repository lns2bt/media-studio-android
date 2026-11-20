package dev.bit.media

import java.util.Locale

private val linkPattern = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

/** Sentence punctuation that follows a link in shared text rather than belonging to it. */
private const val TRAILING = ".,;:!?'\"’”»…。、，"
private val closers = mapOf(')' to '(', ']' to '[', '}' to '{')

/**
 * The first web link in shared or pasted text, without punctuation from the surrounding
 * sentence: "Watch https://youtu.be/abc." and "(https://youtu.be/abc)" both yield the link.
 * Brackets that belong to the link, as in Wikipedia titles, are kept when balanced.
 */
internal fun firstLink(text: String): String? {
    var link = linkPattern.find(text)?.value ?: return null
    while (link.isNotEmpty()) {
        val last = link.last()
        val opener = closers[last]
        link = when {
            last in TRAILING -> link.dropLast(1)
            opener != null && link.count { it == opener } < link.count { it == last } -> link.dropLast(1)
            else -> break
        }
    }
    return normalizeLink(link)
}

/** Trims the link and lowercases its scheme, which yt-dlp's site patterns expect. */
internal fun normalizeLink(text: String): String {
    val link = text.trim()
    val colon = link.indexOf("://")
    return if (colon > 0) link.substring(0, colon).lowercase(Locale.ROOT) + link.substring(colon) else link
}

internal fun validLink(text: String): Boolean = runCatching {
    val uri = java.net.URI(text)
    uri.scheme.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)
