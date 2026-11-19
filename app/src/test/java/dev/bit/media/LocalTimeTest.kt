package dev.bit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTimeTest {
    @Test fun fractionalInputAndMinuteBoundaries() {
        assertEquals(1L, LocalTime.parse("0.001"))
        assertEquals(59990L, LocalTime.parse("0:59.99"))
        assertEquals(3600100L, LocalTime.parse("1:00:00.1"))
        for (ms in listOf(0L, 1L, 59999L, 60000L, 3600001L, Long.MAX_VALUE)) {
            assertEquals(ms, LocalTime.parse(LocalTime.format(ms)))
        }
    }

    @Test fun invalidPrecisionAndOverflowAreRejected() {
        for (text in listOf("-0.1", "0:60", "1:00.0001", "999999999999999999:00", "9223372036854775.808")) {
            assertNull(LocalTime.parse(text))
        }
    }
}
