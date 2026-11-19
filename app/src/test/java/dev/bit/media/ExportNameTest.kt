package dev.bit.media

import org.junit.Assert.*
import org.junit.Test

class ExportNameTest {
    @Test fun preservesFinalExtensionIncludingCase() {
        assertEquals(ExportName("mix.v2", ".MP3"), ExportName.split("mix.v2.MP3"))
        for (name in listOf("recording", ".profile", "mix.")) {
            assertEquals(ExportName(name, ""), ExportName.split(name))
        }
    }

    @Test fun collisionUsesFirstFreeNumberAcrossCaseDifferences() {
        val name = ExportName("original", ".MP3")
        assertEquals("Mix (2)", name.availableBase("Mix", setOf("mix.mp3", "MIX (1).Mp3", "Mix (3).MP3")))
        assertEquals("Mix", name.availableBase("Mix", setOf("Mix.wav")))
    }

    @Test fun handlesUtf8LimitWithoutBreakingEmojiWhenAddingNumber() {
        val base = "🎧".repeat(62) + "abc"
        val name = ExportName(base, ".ogg")
        assertNull(name.problem(base))
        assertNotNull(name.problem(base + "a"))
        assertEquals("🎧".repeat(61) + " (1)", name.availableBase(base, setOf(base + ".ogg")))
    }

    @Test fun rejectsPathsAndNamesThatStorageWouldRewrite() {
        val name = ExportName("sample", ".wav")
        for (bad in listOf("", "..", ".hidden", "../song", "a\\b", "a:b", "a\nb", "mix ", "mix.")) {
            assertNotNull(bad, name.problem(bad))
        }
        assertNull(name.problem("Live mix – take 2"))
    }
}

class ExportBaseTest {
    @org.junit.Test fun titleIsShortenedAndKeepsRangeLabel() {
        val long = "A".repeat(80)
        org.junit.Assert.assertEquals("A".repeat(55) + " · 00m30s–01m00s",
            ExportName.base(long, null, clipLabel(30_000, 60_000)))
        org.junit.Assert.assertEquals("Song_ Live", ExportName.base("Song: Live", null, ""))
        org.junit.Assert.assertEquals("Media", ExportName.base(" .. ", null, ""))
    }

    @org.junit.Test fun customNameReplacesTitleAndRange() {
        val long = "B".repeat(80)
        org.junit.Assert.assertEquals(long, ExportName.base("Title", long, clipLabel(30_000, 60_000)))
        org.junit.Assert.assertEquals("My clip", ExportName.base("Title", " My clip ", " · 00m30s–end"))
    }
}
