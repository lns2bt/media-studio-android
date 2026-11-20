package dev.bit.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A decoded frame; [exact] is false when only the nearest keyframe could be read. */
private class Frame(val image: Bitmap, val exact: Boolean)

/**
 * Decodes single video frames from one source. Calls are serialized because a retriever is
 * not thread-safe; [close] waits for a decode in progress.
 *
 * The file descriptor stays open for the retriever's lifetime: with setDataSource(Context, Uri)
 * Android closes its descriptor right away, and some providers then serve only the first frame.
 * A failed decode reopens the source once, then falls back to the nearest keyframe.
 */
private class FrameSource(private val context: Context, private val uri: Uri) {
    private val lock = Any()
    private var asset: AssetFileDescriptor? = null
    private var retriever: MediaMetadataRetriever? = null
    private var closed = false

    private fun open(): MediaMetadataRetriever {
        retriever?.let { return it }
        val file = requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r")) { "No descriptor" }
        asset = file
        return MediaMetadataRetriever().also {
            if (file.declaredLength < 0) it.setDataSource(file.fileDescriptor)
            else it.setDataSource(file.fileDescriptor, file.startOffset, file.declaredLength)
            retriever = it
        }
    }

    private fun release() {
        runCatching { retriever?.release() }
        runCatching { asset?.close() }
        retriever = null
        asset = null
    }

    /** Null when [wanted] says a newer request replaced this one, or nothing could be decoded. */
    fun frame(ms: Long, durationMs: Long, maxSide: Int, wanted: () -> Boolean): Frame? = synchronized(lock) {
        if (closed || !wanted()) return null
        // The very last instant often has no decodable frame; step back slightly.
        val us = ms.coerceIn(0L, maxOf(0L, durationMs - 40)) * 1000
        fun attempt(option: Int): Bitmap? = try {
            open().getScaledFrameAtTime(us, option, maxSide, maxSide)
        } catch (e: Exception) {
            Log.w(TAG, "Frame decode failed (${e.javaClass.simpleName})")
            null
        }
        attempt(MediaMetadataRetriever.OPTION_CLOSEST)?.let { return Frame(it, true) }
        Log.w(TAG, "No exact frame; reopening the source")
        release()
        if (closed || !wanted()) return null
        attempt(MediaMetadataRetriever.OPTION_CLOSEST)?.let { return Frame(it, true) }
        attempt(MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { return Frame(it, false) }
        Log.w(TAG, "No frame at this position")
        null
    }

    fun close() = synchronized(lock) {
        closed = true
        release()
    }

    private companion object { const val TAG = "StudioFrames" }
}

/**
 * The video frames at the start and end markers, so a cut can be judged by picture as well
 * as by sound. Tapping a frame selects that marker for fine trimming.
 */
@Composable
internal fun MarkerFrames(uri: Uri, durationMs: Long, start: Long, end: Long, selected: Boolean?,
                          select: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val source = remember(uri) { FrameSource(context, uri) }
    DisposableEffect(source) {
        // Releasing can wait for a decode, so it never runs on the main thread.
        onDispose { Thread { source.close() }.apply { isDaemon = true }.start() }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (isStart in listOf(true, false)) {
            FrameTile(source, durationMs, if (isStart) start else end, isStart, selected == isStart,
                Modifier.weight(1f)) { select(isStart) }
        }
    }
}

@Composable
private fun FrameTile(source: FrameSource, durationMs: Long, ms: Long, isStart: Boolean, chosen: Boolean,
                      modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ink = if (isStart) colors.primary else colors.tertiary
    var image by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var exact by remember(source) { mutableStateOf(true) }
    var shownAt by remember(source) { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }
    val latest by rememberUpdatedState(ms)
    LaunchedEffect(source, ms) {
        // Wait for the marker to settle; dragging produces many positions per second.
        delay(140)
        busy = true
        try {
            val frame = withContext(Dispatchers.IO) {
                // A decode that starts after the marker moved again is skipped, so moves never queue up.
                source.frame(ms, durationMs, 360) { latest == ms }
            }
            if (latest != ms) return@LaunchedEffect
            failed = frame == null
            if (frame != null) {
                image = frame.image.asImageBitmap()
                exact = frame.exact
                shownAt = ms
            }
        } finally {
            busy = false
        }
    }
    val name = if (isStart) "Start" else "End"
    // An older picture stays visible but dimmed until the current position is decoded.
    val stale = busy || failed || shownAt != ms
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
            .background(Color.Black)
            .border(BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) ink else colors.outlineVariant),
                MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "$name frame at ${LocalTime.format(ms)}"
                this.selected = chosen
            }) {
            Crossfade(image, label = "$name frame") { frame ->
                if (frame != null) Image(frame, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit, alpha = if (stale) 0.45f else 1f)
            }
            if (failed && !busy) Text("No preview here", Modifier.align(Alignment.Center), color = Color.White,
                style = MaterialTheme.typography.labelMedium)
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp),
                color = Color.White, strokeWidth = 2.5.dp)
            Surface(Modifier.align(Alignment.BottomStart).padding(6.dp), shape = MaterialTheme.shapes.small,
                color = ink, contentColor = if (isStart) colors.onPrimary else colors.onTertiary) {
                // "≈" marks a nearby keyframe shown because the exact frame could not be decoded.
                val approx = if (!exact && !stale) "≈ " else ""
                Text("${name.first()}  $approx${LocalTime.format(ms)}", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
