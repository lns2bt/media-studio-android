package dev.bit.media

import android.net.Uri

data class LocalInfo(val title: String, val durationMs: Long, val video: Boolean, val audio: Boolean)

data class LocalJob(
    val uri: Uri,
    val info: LocalInfo,
    val startMs: Long,
    val endMs: Long,
    val format: LocalFormat,
    val audioOnly: Boolean,
    /** File name chosen by the user, without extension; null derives it from the title. */
    val name: String? = null,
    /** Size and frame rate for GIF and animated WebP exports. */
    val motion: Motion = Motion(),
)

/** Animated image settings: [size] caps the longer side, so small videos are never enlarged. */
data class Motion(val size: Int = 480, val fps: Int = 15) {
    companion object {
        val SIZES = listOf(320, 480, 720)
        val RATES = listOf(10, 15, 24)
    }
}

enum class LocalFormat(val animated: Boolean = false) {
    ORIGINAL, MP3, AAC, OPUS, FLAC, H264, VP9, GIF(animated = true), WEBP(animated = true);
}
