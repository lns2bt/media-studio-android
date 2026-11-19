package dev.bit.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

// No display surface: video sources are auditioned through their audio track.
private class ClipPlayer(private val context: Context, private val uri: Uri) {
    private var player: MediaPlayer? = null
    var active by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0)
        private set
    var error by mutableStateOf(false)
        private set
    var auditioning by mutableStateOf(false)
        private set
    var limit = 0L
        private set

    fun stop(reset: Boolean = true) {
        val old = player
        player = null
        active = false
        playing = false
        auditioning = false
        if (reset) position = 0
        old?.release()
    }

    fun toggle(start: Long, end: Long) {
        if (auditioning) stop()
        if (active) {
            if (playing) position = player?.currentPosition?.toLong() ?: start
            stop(false)
            return
        }
        val resume = position.takeIf { it in start until end } ?: start
        play(resume, end)
    }

    fun audition(start: Long, end: Long) {
        stop()
        auditioning = true
        play(start, end)
    }

    private fun play(resume: Long, end: Long) {
        limit = end
        error = false
        try {
            val p = MediaPlayer()
            player = p
            active = true
            position = resume
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setOnErrorListener { failed, _, _ ->
                if (player === failed) { stop(); error = true }
                true
            }
            p.setOnCompletionListener { if (player === it) stop() }
            fun begin() {
                if (player === p) {
                    p.start()
                    playing = true
                }
            }
            p.setOnPreparedListener {
                if (player === it) {
                    if (resume == 0L) begin() else {
                        it.setOnSeekCompleteListener { begin() }
                        it.seekTo(resume, MediaPlayer.SEEK_CLOSEST)
                    }
                }
            }
            p.setDataSource(context, uri)
            p.prepareAsync()
        } catch (_: Exception) {
            stop()
            error = true
        }
    }

    fun tick() {
        if (!playing) return
        position = player?.currentPosition?.toLong() ?: return
        if (position >= limit) stop()
    }
}

@Composable
internal fun LocalPreview(model: LocalConverterModel, info: LocalInfo) {
    val uri = model.uri ?: return
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val player = remember(uri) { ClipPlayer(context, uri) }
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.stop() }
    }
    LaunchedEffect(player, model.start, model.end, model.rangeError) { player.stop() }
    LaunchedEffect(player, player.playing) {
        while (player.playing) {
            player.tick()
            delay((player.limit - player.position).coerceIn(10L, 40L))
        }
    }

    var boundary by remember(uri) { mutableStateOf<Boolean?>(null) }
    val position = player.position.takeIf { player.active || it > 0 }
    val select: (Boolean) -> Unit = {
        if (it != boundary && player.auditioning) player.stop()
        boundary = it
    }
    LocalTimeline(info.durationMs, model.start, model.end, model.wave,
        position, boundary, select, model::move)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0:00", style = MaterialTheme.typography.labelSmall)
        Text(LocalTime.format(info.durationMs), style = MaterialTheme.typography.labelSmall)
    }
    val hint = when {
        !info.audio -> "No audio track. Use the timeline to trim this video."
        model.loading -> null
        model.wave.isEmpty() -> "Waveform unavailable. You can still preview and trim the audio."
        model.wave.none { it.isFinite() && abs(it) > 0f } -> "This audio track appears silent."
        else -> null
    }
    if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
    boundary?.let { isStart ->
        val current = if (isStart) model.start else model.end
        val low = if (isStart) 0L else model.start + 1
        val high = if (isStart) model.end - 1 else info.durationMs
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val name = if (isStart) "start" else "end"
                Text("Fine trim · $name", style = MaterialTheme.typography.titleSmall)
                key(uri, isStart) {
                    FineWave(info.durationMs, current, low, high, model.start, model.end,
                        model.wave, position, isStart) { model.move(isStart, it) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    for (earlier in listOf(true, false)) {
                        if (!earlier) Text(LocalTime.format(current), style = MaterialTheme.typography.labelLarge)
                        val label = "Move $name ${if (earlier) "earlier" else "later"} by 0.1 seconds"
                        OutlinedButton(onClick = {
                            model.move(isStart, if (earlier) current - minOf(100L, current - low)
                                else current + minOf(100L, high - current))
                        }, enabled = if (earlier) current > low else current < high,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.semantics { contentDescription = label }) {
                            Text(if (earlier) "−0.1 s" else "+0.1 s")
                        }
                    }
                }
                if (info.audio) {
                    val length = minOf(2000L, model.end - model.start)
                    FilledTonalButton(enabled = model.rangeError == null, onClick = {
                        if (player.auditioning) player.stop()
                        else if (isStart) player.audition(model.start, model.start + length)
                        else player.audition(model.end - length, model.end)
                    }) {
                        Text(if (player.auditioning) "Stop audition"
                            else if (length < 2000) "Audition short selection"
                            else if (isStart) "Audition first 2 s" else "Audition last 2 s")
                    }
                }
            }
        }
    } ?: Text("Select a marker to fine-tune that boundary.", style = MaterialTheme.typography.bodySmall)
    if (info.audio) {
        OutlinedButton(enabled = model.rangeError == null,
            onClick = { player.toggle(model.start, model.end) }) {
            Text(when {
                player.auditioning -> "Play selected audio"
                player.playing -> "Pause audio"
                player.active -> "Cancel preview"
                player.position > model.start -> "Resume audio"
                else -> "Play selected audio"
            })
        }
        if (player.error) Text("Audio preview is unavailable for this file.",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun DrawScope.peaks(samples: List<Float>, left: Float, width: Float, amplitude: Float,
    color: (Float) -> Color) {
    if (samples.isEmpty()) return
    // Pool peaks per physical bar so short transients survive downsampling.
    val bars = (width / 3.dp.toPx()).toInt().coerceIn(1, samples.size)
    for (i in 0 until bars) {
        val first = (i.toLong() * samples.size / bars).toInt()
        val next = ((i + 1L) * samples.size / bars).toInt()
        var peak = 0f
        for (j in first until next) if (samples[j].isFinite()) peak = maxOf(peak, abs(samples[j]))
        val height = sqrt(peak.coerceIn(0f, 1f)) * amplitude
        val x = left + width * (i + .5f) / bars
        if (height > 0) drawLine(color(x), Offset(x, center.y - height),
            Offset(x, center.y + height), 2.dp.toPx())
    }
}

@Composable
private fun FineWave(duration: Long, current: Long, low: Long, high: Long, start: Long,
    end: Long, wave: List<Float>, position: Long?, isStart: Boolean, move: (Long) -> Unit) {
    if (duration <= 0) return
    val span = (duration / 4).coerceIn(minOf(400L, duration), 4000L)
    // Follow external edits, but keep time under the finger stable throughout a drag.
    var heldOrigin by remember { mutableStateOf<Long?>(null) }
    val origin = heldOrigin ?: (current - span / 2).coerceIn(0L, duration - span)
    var cursor by remember { mutableDoubleStateOf(0.0) }
    var pixels by remember { mutableIntStateOf(1) }
    val colors = MaterialTheme.colorScheme
    val ink = if (isStart) colors.primary else colors.tertiary
    val name = if (isStart) "start" else "end"
    Canvas(Modifier.fillMaxWidth().height(64.dp)
        .background(colors.surface, MaterialTheme.shapes.small)
        .onSizeChanged { pixels = it.width.coerceAtLeast(1) }
        .draggable(rememberDraggableState { delta ->
            cursor = (cursor + delta.toDouble() / pixels * span)
                .coerceIn(maxOf(origin, low).toDouble(), minOf(origin + span, high).toDouble())
            move(cursor.roundToLong())
        }, Orientation.Horizontal, onDragStarted = {
            cursor = current.toDouble()
            heldOrigin = origin
        }, onDragStopped = { heldOrigin = null })
        .semantics {
            contentDescription = "Fine trim selection $name"
            stateDescription = LocalTime.format(current)
            progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), low.toFloat()..high.toFloat())
            setProgress { move(it.roundToLong().coerceIn(low, high)); true }
        }) {
        fun x(time: Double) = ((time - origin) / span * size.width).toFloat().coerceIn(0f, size.width)
        val left = x(start.toDouble())
        val right = x(end.toDouble())
        val mid = center.y
        drawRect(ink.copy(alpha = .09f), Offset(left, 0f), Size(right - left, size.height))
        drawLine(colors.outlineVariant, Offset(0f, mid), Offset(size.width, mid))
        if (wave.isNotEmpty()) {
            val binMs = duration.toDouble() / wave.size
            val first = (origin / binMs).toInt().coerceIn(wave.indices)
            val last = ((origin + span) / binMs).toInt().coerceIn(wave.indices)
            // Each rectangle occupies the actual time interval summarized by one source peak.
            for (i in first..last) {
                val a = x(i * binMs)
                val b = x((i + 1) * binMs)
                val v = wave[i]
                if (v.isFinite() && b > a) {
                    val h = sqrt(abs(v).coerceAtMost(1f)) * mid * .78f
                    drawRect(ink.copy(alpha = .58f), Offset(a, mid - h), Size(b - a, h * 2))
                }
            }
        }
        position?.takeIf { it in origin..(origin + span) }?.let {
            val p = x(it.toDouble())
            drawLine(colors.onSurface, Offset(p, 0f), Offset(p, size.height), 1.dp.toPx())
        }
        val mark = x(current.toDouble())
        drawLine(ink, Offset(mark, 0f), Offset(mark, size.height), 3.dp.toPx())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(LocalTime.format(origin), style = MaterialTheme.typography.labelSmall)
        Text(LocalTime.format(origin + span), style = MaterialTheme.typography.labelSmall)
    }
    if (wave.isNotEmpty() && duration.toDouble() / wave.size > span.toDouble() / pixels) {
        val bin = LocalTime.format((duration.toDouble() / wave.size).roundToLong().coerceAtLeast(1L))
        Text("One peak covers about $bin. Use audio to judge cuts within a peak.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun LocalTimeline(duration: Long, start: Long, end: Long, wave: List<Float>, position: Long?,
    selected: Boolean?, select: (Boolean) -> Unit, move: (Boolean, Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(112.dp)
        .background(colors.surfaceContainer, MaterialTheme.shapes.medium),
        contentAlignment = AbsoluteAlignment.TopLeft) {
        val inset = with(density) { 24.dp.toPx() }
        val width = (constraints.maxWidth - 2 * inset).coerceAtLeast(1f)
        fun x(ms: Long) = inset + (width * (ms.toDouble() / duration)).toFloat()
        Canvas(Modifier.fillMaxSize()) {
            val top = 28.dp.toPx()
            val bottom = size.height - top
            val mid = size.height / 2
            drawRect(colors.primaryContainer, Offset(x(start), top), Size(x(end) - x(start), bottom - top))
            drawLine(colors.outline, Offset(inset, mid), Offset(inset + width, mid))
            peaks(wave, inset, width, mid - top) { xx ->
                if (xx in x(start)..x(end)) colors.primary else colors.outline
            }
            drawLine(colors.primary, Offset(x(start), 24.dp.toPx()), Offset(x(start), bottom), 2.dp.toPx())
            drawLine(colors.tertiary, Offset(x(end), top), Offset(x(end), size.height - 24.dp.toPx()), 2.dp.toPx())
            position?.let {
                drawLine(colors.onSurface, Offset(x(it.coerceIn(start, end)), top),
                    Offset(x(it.coerceIn(start, end)), bottom), 2.dp.toPx())
            }
        }
        listOf(true, false).forEach { isStart ->
            val current = if (isStart) start else end
            // Keep the touch targets in separate lanes even for a one-millisecond selection.
            val latest by rememberUpdatedState(current)
            var cursor by remember { mutableDoubleStateOf(0.0) }
            val low = if (isStart) 0L else start + 1
            val high = if (isStart) end - 1 else duration
            Box(Modifier.absoluteOffset {
                IntOffset((x(current) - inset).roundToInt(), if (isStart) 0 else 64.dp.roundToPx())
            }.size(48.dp)
                .clickable { select(isStart) }
                .draggable(rememberDraggableState { delta ->
                    cursor = (cursor + delta.toDouble() / width * duration)
                        .coerceIn(low.toDouble(), high.toDouble())
                    move(isStart, cursor.roundToLong())
                }, Orientation.Horizontal, onDragStarted = { select(isStart); cursor = latest.toDouble() })
                .clearAndSetSemantics {
                    contentDescription = if (isStart) "Selection start" else "Selection end"
                    stateDescription = LocalTime.format(current)
                    this.selected = selected == isStart
                    onClick("Fine-tune boundary") { select(isStart); true }
                    progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), low.toFloat()..high.toFloat())
                    setProgress { value -> select(isStart); move(isStart, value.roundToLong()); true }
                }, contentAlignment = Alignment.Center) {
                val markerColor = if (isStart) colors.primary else colors.tertiary
                Surface(shape = CircleShape, color = markerColor,
                    border = if (selected == isStart) androidx.compose.foundation.BorderStroke(
                        2.dp, colors.onSurface) else null) {
                    Text(if (isStart) "S" else "E", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
