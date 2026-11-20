package dev.bit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemsTest {
    @Test fun stripsEnginePrefixes() {
        assertEquals("Private video. Sign in if you've been granted access",
            Problems.clean("ERROR: [youtube] dQw4w9WgXcQ: Private video. Sign in if you've been granted access"))
        assertEquals("Unsupported URL: https://example.com/",
            Problems.clean("ERROR: Unsupported URL: https://example.com/"))
        assertEquals("Cannot write to Downloads.", Problems.clean("Cannot write to Downloads."))
    }

    @Test fun explainsKnownFailures() {
        val private = Problems.explain("ERROR: [youtube] abc: Private video. Sign in if you've been granted access")
        assertEquals("This video is private.", private.message)
        assertEquals("Private video. Sign in if you've been granted access", private.detail)
        assertFalse(private.update)

        assertTrue(Problems.explain("ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies").update)
        assertTrue(Problems.explain("ERROR: [youtube] abc: Requested format is not available").update)
        assertTrue(Problems.explain("ERROR: Unsupported URL: https://example.com/x").update)
        assertEquals("This video is age-restricted and needs a signed-in account, which Media Studio doesn't support.",
            Problems.explain("ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate").message)
        assertEquals("Can't reach the website. Check your internet connection and try again.",
            Problems.explain("ERROR: [generic] Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>").message)
        assertEquals("This video isn't available in your country.",
            Problems.explain("ERROR: [youtube] abc: The uploader has not made this video available in your country").message)
        assertEquals("Not enough free storage on this phone. Free up some space and try again.",
            Problems.explain("write failed: ENOSPC (No space left on device)").message)
    }

    @Test fun keepsUnknownAndOwnMessages() {
        val own = Problems.explain("Please share a link to a single media item.")
        assertEquals("Please share a link to a single media item.", own.message)
        assertNull(own.detail)
        assertEquals("Something went wrong. Try again.", Problems.explain(null).message)
        assertEquals("Something went wrong. Try again.", Problems.explain("  ").message)
    }
}
