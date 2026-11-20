package dev.bit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedImageTest {
    @Test fun gifUsesOnePaletteAndLoops() {
        val args = AnimatedImage.outputArgs(LocalFormat.GIF, Motion(size = 320, fps = 10))
        val filter = args[args.indexOf("-vf") + 1]
        assertTrue(filter.startsWith("fps=10,scale=w=if(gte(iw\\,ih)\\,min(320\\,iw)\\,-2):h=if(gte(iw\\,ih)\\,-2\\,min(320\\,ih))"))
        assertTrue("palettegen" in filter && "paletteuse" in filter)
        assertEquals("0", args[args.indexOf("-loop") + 1])
        assertEquals("gif", args.last())
        assertEquals("gif", AnimatedImage.extension(LocalFormat.GIF))
    }

    @Test fun webpUsesTheAnimatedEncoder() {
        val args = AnimatedImage.outputArgs(LocalFormat.WEBP, Motion())
        assertEquals("libwebp_anim", args[args.indexOf("-c:v") + 1])
        assertEquals("fps=15,scale=w=if(gte(iw\\,ih)\\,min(480\\,iw)\\,-2):h=if(gte(iw\\,ih)\\,-2\\,min(480\\,ih)):flags=lanczos",
            args[args.indexOf("-vf") + 1])
        assertEquals("webp", AnimatedImage.extension(LocalFormat.WEBP))
    }
}
