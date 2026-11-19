package dev.bit.media

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A small audio envelope. The playhead reveals measured work; an unmeasured job scans the track. */
@Composable
internal fun StudioProgress(percent: Int?) {
    val fraction = percent?.coerceIn(0, 100)?.div(100f)
    val motion = (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
    val playhead = if (fraction != null && motion)
        animateFloatAsState(fraction, tween(240), label = "Completed work") else null
    val scan = if (fraction == null && motion)
        rememberInfiniteTransition(label = "Processing").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "Scan position"
        ) else null
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val semantics = if (fraction == null) Modifier.progressSemantics() else Modifier.progressSemantics(fraction)
    Spacer(Modifier.fillMaxWidth().height(28.dp).then(semantics).drawWithCache {
        val bars = (size.width / 6.dp.toPx()).toInt().coerceIn(2, 120)
        val step = size.width / bars
        val width = (step * 0.57f).coerceAtMost(4.dp.toPx())
        val heights = FloatArray(bars) { i ->
            val t = (i + 0.5f) / bars
            val envelope = (0.5 - 0.5 * cos(2 * PI * t)).toFloat()
            val beat = (0.5 + 0.5 * sin(11 * PI * t) * cos(4 * PI * t)).toFloat()
            size.height * (0.07f + envelope * beat * 0.35f)
        }
        onDrawBehind {
            val value = playhead?.value ?: fraction ?: 0f
            val center = (scan?.value ?: 0.5f) * 0.7f + 0.15f
            for (i in heights.indices) {
                val t = (i + 0.5f) / bars
                val x = size.width * (if (layoutDirection == LayoutDirection.Rtl) 1f - t else t)
                val lit = if (fraction != null) t <= value else kotlin.math.abs(t - center) < 0.15f
                drawLine(if (lit) active else track, Offset(x, size.height / 2 - heights[i]),
                    Offset(x, size.height / 2 + heights[i]), width, StrokeCap.Round)
            }
        }
    })
}
