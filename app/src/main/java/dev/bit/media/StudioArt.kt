package dev.bit.media

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Media Studio's mark — a waveform cut by two trim brackets — in the shapes the launcher
 * icon uses (see tools/brand.py), drawn with the current theme's colours.
 */
internal val BrandMark: ImageVector = ImageVector.Builder("BrandMark", 24.dp, 24.dp, 24f, 24f).apply {
    fun stroke(d: String, width: Float, alpha: Float = 1f) = addPath(
        PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeAlpha = alpha,
        strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
    stroke("M2,11 L2,13 M22,11 L22,13", 1.8f, 0.5f)
    stroke("M9,9.5 L9,14.5 M12,7 L12,17 M15,8.5 L15,15.5", 2.2f)
    stroke("M6.5,5 H4.5 V19 H6.5 M17.5,5 H19.5 V19 H17.5", 1.8f)
}.build()

/** A speech-like envelope in 0..1 with its loudest part between [from] and [to] (fractions). */
internal fun waveEnvelope(count: Int, from: Float, to: Float, seed: Float = 1.7f): List<Float> {
    val mid = (from + to) / 2
    val span = to - from
    val words = listOf(
        Triple(0.06f, 0.05f, 0.35f), Triple(0.17f, 0.05f, 0.5f), Triple(from + span * 0.22f, span * 0.17f, 0.75f),
        Triple(mid + span * 0.05f, span * 0.16f, 1f), Triple(to - span * 0.18f, span * 0.14f, 0.7f),
        Triple(0.86f, 0.05f, 0.45f), Triple(0.96f, 0.04f, 0.3f),
    )
    return List(count) { i ->
        val t = i / (count - 1f)
        val level = words.sumOf { (c, w, amp) -> amp * exp(-((t - c) / w).let { it * it }.toDouble()) }.toFloat()
        val noise = (sin(i * 12.9898 + seed * 78.233) * 43758.5453).let { it - kotlin.math.floor(it) }.toFloat()
        ((0.08f + level) * (0.78f + 0.22f * noise)).coerceIn(0.07f, 1f)
    }
}

/** True unless the person turned animations off in the system settings. */
@Composable
private fun motionAllowed(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

private const val GLYPH = 1.3f

private val chain by lazy {
    PathParser().parsePathString(
        "M21.5,26.5 L26.5,21.5 M19,23 L16.5,25.5 A4.6,4.6 0 0 0 22.5,31.5 L25,29 " +
            "M29,25 L31.5,22.5 A4.6,4.6 0 0 0 25.5,16.5 L23,19"
    ).toPath()
}
private val document by lazy { PathParser().parsePathString("M17,13 H27 L32,18 V35 H17 Z M27,13 V18 H32").toPath() }

/**
 * The empty-state picture for Extractor ([fromLink]) and Converter: a source flowing into a
 * waveform whose middle is selected. The bars breathe gently unless animations are off.
 */
@Composable
internal fun TrimIllustration(fromLink: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val moving = motionAllowed()
    val phase by if (moving) rememberInfiniteTransition(label = "wave").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "phase",
    ) else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val count = 26
    val first = 10
    val last = 19
    val heights = remember { waveEnvelope(count, first / (count - 1f), (last - 1) / (count - 1f)) }
    val sourceTint = if (fromLink) scheme.tertiary else scheme.secondary
    val sourceFill = if (fromLink) scheme.tertiaryContainer else scheme.secondaryContainer

    Canvas(modifier.fillMaxWidth().height(120.dp).clearAndSetSemantics { }) {
        val unit = size.height / 120f
        // The source: a link or a file on a soft disc, with a dotted path into the waveform.
        val disc = 26f * unit
        val discCenter = Offset(disc + 4f * unit, size.height / 2)
        drawCircle(sourceFill, disc, discCenter)
        withTransform({
            translate(discCenter.x - 24f * unit * GLYPH, discCenter.y - 24f * unit * GLYPH)
            scale(GLYPH * unit, GLYPH * unit, Offset.Zero)
        }) {
            drawPath(if (fromLink) chain else document, sourceTint,
                style = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val waveStart = discCenter.x + disc + 26f * unit
        drawLine(sourceTint.copy(alpha = 0.6f), Offset(discCenter.x + disc + 6f * unit, discCenter.y),
            Offset(waveStart - 10f * unit, discCenter.y), strokeWidth = 2.5f * unit, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 7f * unit), -phase * 24f * unit))

        // The waveform, with empty slots where the brackets stand.
        val waveEnd = size.width - 6f * unit
        val step = (waveEnd - waveStart) / (count - 1)
        val maxBar = size.height * 0.62f
        val left = waveStart + (first - 1) * step
        val right = waveStart + last * step
        val top = size.height * 0.1f
        val bottom = size.height * 0.9f
        drawRoundRect(scheme.primary.copy(alpha = 0.1f), Offset(left, top), Size(right - left, bottom - top),
            CornerRadius(10f * unit))
        heights.forEachIndexed { i, v ->
            if (i == first - 1 || i == last) return@forEachIndexed
            val selected = i in first until last
            val breathe = if (moving) 0.88f + 0.12f * sin(2 * PI * (phase + i * 0.07)).toFloat() else 1f
            val h = (8f * unit + v * maxBar) * breathe
            val x = waveStart + i * step
            drawLine(if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.28f),
                Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2),
                strokeWidth = (step * 0.42f).coerceAtMost(7f * unit), cap = StrokeCap.Round)
        }
        bracket(left, top, bottom, 1f, scheme.primary, unit)
        bracket(right, top, bottom, -1f, scheme.primary, unit)
    }
}

private fun DrawScope.bracket(x: Float, top: Float, bottom: Float, direction: Float, color: Color, unit: Float) {
    val arm = 9f * unit * direction
    val path = Path().apply {
        moveTo(x + arm, top); lineTo(x, top); lineTo(x, bottom); lineTo(x + arm, bottom)
    }
    drawPath(path, color, style = Stroke(3.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
