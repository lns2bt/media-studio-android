package dev.bit.media

import java.util.Locale

/**
 * A failure explained for people: [message] says what happened and what helps, [detail] keeps
 * the engine's original text, and [update] marks failures a newer yt-dlp often fixes.
 */
data class Problem(val message: String, val detail: String?, val update: Boolean = false)

internal object Problems {
    private class Rule(val message: String, val update: Boolean, vararg val needles: String)

    // Order matters: specific causes before broad ones such as network or "HTTP Error".
    private val rules = listOf(
        Rule("YouTube wants a sign-in to confirm you're not a bot. Update yt-dlp or try again later.", true,
            "not a bot"),
        Rule("This video is age-restricted and needs a signed-in account, which Media Studio doesn't support.", false,
            "confirm your age", "age-restricted", "age restricted", "inappropriate for some users"),
        Rule("This video is private.", false, "private video", "video is private"),
        Rule("This video is only available to channel members or paying viewers.", false,
            "members-only", "members only", "join this channel", "requires payment", "purchase this"),
        Rule("This video isn't available in your country.", false,
            "available in your country", "geo restrict", "geo-restrict", "geo-block"),
        Rule("This video is unavailable. It may have been removed, or the link is incomplete.", false,
            "video unavailable", "has been removed", "has been terminated", "no longer available",
            "does not exist", "http error 404", "http error 410"),
        Rule("This website isn't supported. A newer yt-dlp may add support.", true, "unsupported url"),
        Rule("Not enough free storage on this phone. Free up some space and try again.", false,
            "no space left", "enospc"),
        Rule("Can't reach the website. Check your internet connection and try again.", false,
            "unable to resolve host", "name resolution", "no address associated", "network is unreachable",
            "name or service not known", "timed out", "connection reset", "connection refused",
            "failed to establish a new connection", "unable to download webpage"),
        Rule("The website changed something yt-dlp can't handle yet. Updating yt-dlp usually fixes this.", true,
            "http error 403", "requested format is not available", "unable to extract", "nsig extraction",
            "signature extraction", "unable to download video data", "please report this issue",
            "no video formats found"),
        Rule("Processing the media failed. Try a different format or time range.", false,
            "ffmpeg exited with code", "conversion failed", "invalid data found when processing input"),
    )

    private val prefix = Regex("""^(?:ERROR:\s*)?(?:\[[\w:.-]+]\s+(?:[^\s:]+:\s+)?)?""")

    /** The engine's message without "ERROR:" and the "[site] id:" prefix. */
    fun clean(raw: String): String = raw.trim().replace(prefix, "").trim()

    fun explain(raw: String?): Problem {
        val text = clean(raw.orEmpty()).ifBlank { return Problem("Something went wrong. Try again.", null) }
        val lower = text.lowercase(Locale.ROOT)
        val rule = rules.firstOrNull { rule -> rule.needles.any { it in lower } }
            ?: return Problem(text, null)
        return Problem(rule.message, text, rule.update)
    }
}
