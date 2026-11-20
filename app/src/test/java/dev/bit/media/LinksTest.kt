package dev.bit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test fun dropsSentencePunctuation() {
        assertEquals("https://youtu.be/abc", firstLink("Watch this: https://youtu.be/abc."))
        assertEquals("https://youtu.be/abc", firstLink("Wow https://youtu.be/abc!!"))
        assertEquals("https://youtu.be/abc", firstLink("(see https://youtu.be/abc)"))
        assertEquals("https://youtu.be/abc", firstLink("„https://youtu.be/abc“".replace('“', '”')))
        assertEquals("https://example.com/a?b=1", firstLink("Link: https://example.com/a?b=1, more text"))
    }

    @Test fun keepsBalancedBracketsAndQueries() {
        assertEquals("https://en.wikipedia.org/wiki/Mercury_(planet)",
            firstLink("https://en.wikipedia.org/wiki/Mercury_(planet)"))
        assertEquals("https://en.wikipedia.org/wiki/Mercury_(planet)",
            firstLink("(https://en.wikipedia.org/wiki/Mercury_(planet))."))
        assertEquals("https://www.youtube.com/watch?v=abc&t=90s", firstLink("https://www.youtube.com/watch?v=abc&t=90s"))
    }

    @Test fun normalizesTheScheme() {
        assertEquals("https://YouTu.be/abc", firstLink("HTTPS://YouTu.be/abc"))
        assertEquals("https://youtu.be/abc", normalizeLink("  Https://youtu.be/abc "))
        assertTrue(validLink("HTTPS://youtu.be/abc"))
        assertFalse(validLink("ftp://example.com/x"))
        assertFalse(validLink("https://"))
        assertNull(firstLink("no link here"))
    }
}
