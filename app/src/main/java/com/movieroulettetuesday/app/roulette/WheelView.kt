package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.catalog.displayColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Brush
import com.movieroulettetuesday.app.catalog.CategoryPalette
import com.movieroulettetuesday.app.catalog.displayHex
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieroulettetuesday.app.catalog.HexColor
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.ui.theme.BrandColors

/**
 * The category wheel (Roulette/WheelView.swift): slices drawn from the same [WheelGeometry] the
 * winner math uses, a fixed pointer at 12 o'clock, and a hub button that spins.
 */
@Composable
fun RouletteWheel(
    categories: List<RouletteCategory>,
    geometry: WheelGeometry,
    rotation: Double,
    spin: RouletteViewModel.SpinRun?,
    canSpin: Boolean,
    onSpin: () -> Unit,
    onSpinFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
    haptics: Boolean = false,
    /** Tap on the spinning wheel (or hub) = stop early. */
    onStop: (() -> Unit)? = null,
) {
    // Drawn angle (degrees). Driven by a frame loop that follows the click-locked SpinMotion (and a
    // tap-to-stop plan when present); haptic ticks fire at the same crossing instants.
    var angle by remember { mutableDoubleStateOf(rotation) }
    val latestSpin by rememberUpdatedState(spin)
    val latestHaptics by rememberUpdatedState(haptics)
    val view = LocalView.current
    LaunchedEffect(spin?.token) {
        val run = spin
        if (run == null) {
            angle = rotation
            return@LaunchedEffect
        }
        val t0 = withFrameNanos { it }
        var tickIndex = 0
        var ticksFor: SpinStopPlan? = null
        var ticks = run.hapticTimes(geometry)
        while (true) {
            val now = withFrameNanos { it }
            val current = latestSpin?.takeIf { it.token == run.token } ?: run
            val t = (now - t0) / 1e9
            if (current.stop != ticksFor) {
                ticksFor = current.stop
                ticks = current.hapticTimes(geometry)
                tickIndex = ticks.indexOfFirst { it > t }.let { if (it < 0) ticks.size else it }
            }
            var crossed = false
            while (tickIndex < ticks.size && ticks[tickIndex] <= t) { tickIndex++; crossed = true }
            if (crossed && latestHaptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (t >= current.endTime) {
                angle = current.finalRotation
                break
            }
            angle = current.angleAt(t)
        }
        onSpinFinished(run.token)
    }
    val measurer = rememberTextMeasurer()
    val rim = BrandColors.WheelRimLight
    val label = categories.getOrNull(geometry.segmentIndex(rotation))?.displayName
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .testTag("wheel")
            .semantics {
                contentDescription = "Roulette wheel"
                if (label != null && spin == null) stateDescription = "Pointer on $label"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = (angle % 360.0).toFloat() }
                .clip(CircleShape)
                .clickable(
                    enabled = canSpin || (spin != null && spin.stop == null && onStop != null),
                    onClickLabel = if (spin != null) "Stop" else "Spin",
                ) { if (spin != null) onStop?.invoke() else onSpin() },
        ) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            val tl = Offset(c.x - r, c.y - r)
            geometry.segments.forEachIndexed { i, seg ->
                val cat = categories.getOrNull(i)
                val stops = CategoryPalette.radialStops(cat?.displayHex ?: "#808080")
                    .map { h -> HexColor.parse(h)?.let { Color(it.argb) } ?: Color.Gray }
                val brush = Brush.radialGradient(stops, center = c, radius = r)
                // Compose arcs start at 3 o'clock; wheel angles start at 12 o'clock.
                drawArc(brush, (seg.startAngle - 90).toFloat(), seg.sweep.toFloat(), true, tl, Size(2 * r, 2 * r))
                drawArc(Color.White.copy(alpha = 0.5f), (seg.startAngle - 90).toFloat(), seg.sweep.toFloat(), true, tl, Size(2 * r, 2 * r), style = Stroke(1.dp.toPx()))
                if (cat != null) {
                    val dark = cat.displayColor.prefersDarkText
                    val text = measurer.measure(
                        cat.name,
                        TextStyle(
                            color = if (dark) BrandColors.Ink else Color.White,
                            fontSize = (r / 14f).toSp(),
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = (r * 0.58f).toInt()),
                    )
                    // Labels read outward on the right half and are flipped on the left half so none is upside down.
                    val flip = seg.midAngle > 180
                    rotate((seg.midAngle - 90 + if (flip) 180 else 0).toFloat(), pivot = c) {
                        val x = if (flip) c.x - r * 0.92f else c.x + r * 0.92f - text.size.width
                        drawText(text, topLeft = Offset(x, c.y - text.size.height / 2f))
                    }
                }
            }
            drawCircle(rim, r, c, style = Stroke(r * 0.04f))
        }
        // Fixed pointer at 12 o'clock.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val w = r * 0.09f
            val top = size.height / 2 - r - w * 0.2f
            val p = Path().apply {
                moveTo(size.width / 2 - w, top)
                lineTo(size.width / 2 + w, top)
                lineTo(size.width / 2, top + w * 1.9f)
                close()
            }
            drawPath(p, BrandColors.Ink)
        }
        // Hub button.
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(
                    enabled = canSpin || (spin != null && spin.stop == null && onStop != null),
                    onClickLabel = if (spin != null) "Stop the wheel" else "Spin the wheel",
                ) { if (spin != null) onStop?.invoke() else onSpin() }
                .testTag("spin-hub"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (spin != null) (if (spin.stop == null && onStop != null) "STOP" else "…") else "SPIN",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
            )
        }
    }
}
