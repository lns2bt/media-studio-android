package dev.bit.media

/** FFmpeg settings for GIF and animated WebP exports of a video selection. */
internal object AnimatedImage {
    fun extension(format: LocalFormat): String = when (format) {
        LocalFormat.GIF -> "gif"
        LocalFormat.WEBP -> "webp"
        else -> error("$format is not an animated image format")
    }

    /**
     * Output options after `-map` for the video stream. [Motion.size] limits the longer side,
     * so portrait phone videos stay as small as landscape ones and small sources are never
     * enlarged; `-2` keeps the other side even and the aspect ratio intact.
     */
    fun outputArgs(format: LocalFormat, motion: Motion): List<String> {
        val s = motion.size
        val scale = "scale=w=if(gte(iw\\,ih)\\,min($s\\,iw)\\,-2):h=if(gte(iw\\,ih)\\,-2\\,min($s\\,ih)):flags=lanczos"
        val base = "fps=${motion.fps},$scale"
        return when (format) {
            // One palette for the whole clip, built from the pixels that change, keeps GIFs
            // sharp and small; rectangle diffing only re-encodes the moving region.
            LocalFormat.GIF -> listOf(
                "-vf", "$base,split[a][b];[a]palettegen=max_colors=256:stats_mode=diff[p];" +
                    "[b][p]paletteuse=dither=sierra2_4a:diff_mode=rectangle",
                "-loop", "0", "-f", "gif",
            )
            LocalFormat.WEBP -> listOf(
                "-vf", base, "-c:v", "libwebp_anim", "-lossless", "0", "-quality", "75",
                "-compression_level", "4", "-loop", "0", "-f", "webp",
            )
            else -> error("$format is not an animated image format")
        }
    }

    /** Animated images grow with every frame; past this length a hint suggests a shorter clip. */
    const val LONG_CLIP_MS = 15_000L
}
