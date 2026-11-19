package dev.bit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormTimeTest {
    @Test fun formatsAndReadsClockBoundaries() {
        val examples = mapOf(0 to "0:00", 59 to "0:59", 60 to "1:00", 3599 to "59:59",
            3600 to "1:00:00", 3661 to "1:01:01", Int.MAX_VALUE to "596523:14:07")
        for ((seconds, text) in examples) {
            assertEquals(text, FormTime.format(seconds))
            assertEquals(FormRange.Valid(seconds, null), FormTime.range(text, "", null))
        }
        assertEquals(FormRange.Valid(3601, null), FormTime.range(" 60:01 ", " ", null))
    }

    @Test fun prefilledDurationMeansUntilEnd() {
        val prefill = FormTime.format(763)
        assertEquals(FormRange.Valid(0, null), FormTime.range("0:00", prefill, 763))
        assertEquals(FormRange.Valid(62, null), FormTime.range("1:02", prefill, 763))
    }

    @Test fun preservesExplicitCutsAndOpenEndedRanges() {
        assertEquals(FormRange.Valid(0, 762), FormTime.range("0:00", "12:42", 763))
        assertEquals(FormRange.Valid(62, 763), FormTime.range("1:02", "12:43", null))
        assertEquals(FormRange.Valid(62, null), FormTime.range("1:02", "", 763))
        assertEquals(FormRange.Valid(0, null), FormTime.range("0:00", "", null))
    }

    @Test fun rejectsEmptyReversedAndOutOfBoundsRanges() {
        for ((start, end) in listOf("0:00" to "0:00", "0:07" to "0:06", "0:10" to "",
            "0:11" to "", "0:09" to "0:11", "" to "0:02")) {
            assertTrue("$start to $end", FormTime.range(start, end, 10) is FormRange.Invalid)
        }
    }

    @Test fun rejectsMalformedOrOverflowingTimeInEitherField() {
        for (text in listOf("-1:00", "1", "1:2", "0:60", "1:60:00", "1:2:03", "1:02:03:04",
            "0:01.5", "596523:14:08", "99999999999999999999:00")) {
            assertTrue(text, FormTime.range(text, "", null) is FormRange.Invalid)
            assertTrue(text, FormTime.range("0:00", text, null) is FormRange.Invalid)
        }
    }
}
