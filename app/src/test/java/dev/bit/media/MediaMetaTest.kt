package dev.bit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMetaTest {
    private val formats = listOf(
        SourceFormat(null, video = false, audio = true, bytes = 3_000_000),
        SourceFormat(null, video = false, audio = true, bytes = 1_000_000),
        SourceFormat(1080, video = true, audio = false, bytes = 40_000_000),
        SourceFormat(1080, video = true, audio = false, bytes = 30_000_000),
        SourceFormat(720, video = true, audio = false, bytes = 20_000_000),
        SourceFormat(360, video = true, audio = true, bytes = 8_000_000),
        SourceFormat(90, video = true, audio = false, bytes = null),
    )

    @Test fun listsDistinctUsableHeightsBestFirst() {
        assertEquals(listOf(1080, 720, 360), MediaMeta.qualities(formats))
    }

    @Test fun estimatesSelectedStreams() {
        assertEquals(43_000_000L, MediaMeta.estimate(formats, Mode.BOTH, null, 1.0))
        assertEquals(23_000_000L, MediaMeta.estimate(formats, Mode.BOTH, 720, 1.0))
        assertEquals(20_000_000L, MediaMeta.estimate(formats, Mode.VIDEO, 720, 1.0))
        assertEquals(3_000_000L, MediaMeta.estimate(formats, Mode.AUDIO, 720, 1.0))
        assertEquals(1_500_000L, MediaMeta.estimate(formats, Mode.AUDIO, null, 0.5))
    }

    @Test fun fallsBackToCombinedOrUnknown() {
        val single = listOf(SourceFormat(480, video = true, audio = true, bytes = 5_000_000))
        assertEquals(5_000_000L, MediaMeta.estimate(single, Mode.BOTH, null, 1.0))
        assertEquals(5_000_000L, MediaMeta.estimate(single, Mode.AUDIO, null, 1.0))
        assertNull(MediaMeta.estimate(listOf(SourceFormat(720, true, true, null)), Mode.BOTH, null, 1.0))
        assertNull(MediaMeta.estimate(emptyList(), Mode.AUDIO, null, 1.0))
    }

    @Test fun readsStartOffsetsFromLinks() {
        assertEquals(90, LinkTime.start("https://youtu.be/abc?t=90"))
        assertEquals(90, LinkTime.start("https://www.youtube.com/watch?v=abc&t=1m30s"))
        assertEquals(3723, LinkTime.start("https://example.com/v?t=1h2m3s"))
        assertEquals(42, LinkTime.start("https://example.com/v?start=42s"))
        assertEquals(90, LinkTime.start("https://vimeo.com/123#t=1:30"))
        assertNull(LinkTime.start("https://example.com/v?t=0"))
        assertNull(LinkTime.start("https://example.com/v?tab=3"))
        assertNull(LinkTime.start("https://example.com/v?t=soon"))
        assertNull(LinkTime.start("not a link"))
    }
}
