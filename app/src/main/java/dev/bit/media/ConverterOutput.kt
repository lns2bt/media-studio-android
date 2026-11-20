package dev.bit.media

/** Choices concern the output tracks, not the input container. */
internal class ConverterOutput(videoSource: Boolean, audioOnly: Boolean) {
    private val extracting = videoSource && audioOnly
    val default = if (extracting) LocalFormat.MP3 else LocalFormat.ORIGINAL
    val formats = buildList {
        if (!extracting) add(LocalFormat.ORIGINAL)
        if (audioOnly || !videoSource) addAll(listOf(LocalFormat.MP3, LocalFormat.AAC, LocalFormat.OPUS, LocalFormat.FLAC))
        else addAll(listOf(LocalFormat.H264, LocalFormat.VP9, LocalFormat.GIF, LocalFormat.WEBP))
    }
}
