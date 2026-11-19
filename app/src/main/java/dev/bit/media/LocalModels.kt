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
)

enum class LocalFormat { ORIGINAL, MP3, AAC, OPUS, FLAC, H264, VP9 }
