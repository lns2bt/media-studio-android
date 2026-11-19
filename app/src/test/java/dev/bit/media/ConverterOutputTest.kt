package dev.bit.media

import org.junit.Assert.*
import org.junit.Test

class ConverterOutputTest {
    @Test fun switchingVideoToAudioSelectsMp3AndRemovesVideoFormats() {
        val video = ConverterOutput(videoSource = true, audioOnly = false)
        val extracted = ConverterOutput(videoSource = true, audioOnly = true)
        assertEquals(LocalFormat.ORIGINAL, video.default)
        assertEquals(LocalFormat.MP3, extracted.default)
        assertTrue(extracted.formats.contains(extracted.default))
        assertTrue(extracted.formats.intersect(video.formats.toSet()).isEmpty())
    }

    @Test fun openingAudioPreservesOriginalAndOffersAudioConversions() {
        val opened = ConverterOutput(videoSource = false, audioOnly = true)
        assertEquals(LocalFormat.ORIGINAL, opened.default)
        assertTrue(opened.formats.containsAll(listOf(opened.default, LocalFormat.AAC, LocalFormat.OPUS)))
        assertFalse(LocalFormat.H264 in opened.formats)
    }
}
