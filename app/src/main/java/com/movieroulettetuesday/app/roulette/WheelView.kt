package com.movieroulettetuesday.app.roulette

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
) {
    val angle = remember { Animatable(rotation.toFloat()) }
    // A light tick each time the pointer crosses into another slice while spinning (iOS per-click haptics).
    val view = LocalView.current
    LaunchedEffect(spin?.token, haptics, geometry) {
        if (spin == null || !haptics) return@LaunchedEffect
        snapshotFlow { geometry.segmentIndex(angle.value.toDouble()) }
            .distinctUntilChanged()
            .drop(1)
            .collect { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
    LaunchedEffect(spin?.token) {
        if (spin == null) {
            angle.snapTo(rotation.toFloat())
            return@LaunchedEffect
        }
        angle.snapTo(spin.from.toFloat())
        angle.animateTo(spin.to.toFloat(), tween(durationMillis = spin.durationMs, easing = SpinMotion.easing))
        onSpinFinished(spin.token)
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
                .graphicsLayer { rotationZ = angle.value % 360f }
                .clip(CircleShape)
                .clickable(enabled = canSpin, onClickLabel = "Spin", onClick = onSpin),
        ) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            val tl = Offset(c.x - r, c.y - r)
            geometry.segments.forEachIndexed { i, seg ->
                val cat = categories.getOrNull(i)
                val color = cat?.let { HexColor.parse(it.colorHex) }?.let { Color(it.argb) } ?: Color.Gray
                // Compose arcs start at 3 o'clock; wheel angles start at 12 o'clock.
                drawArc(color, (seg.startAngle - 90).toFloat(), seg.sweep.toFloat(), true, tl, Size(2 * r, 2 * r))
                drawArc(Color.White.copy(alpha = 0.5f), (seg.startAngle - 90).toFloat(), seg.sweep.toFloat(), true, tl, Size(2 * r, 2 * r), style = Stroke(1.dp.toPx()))
                if (cat != null) {
                    val dark = HexColor.parse(cat.colorHex)?.prefersDarkText == true
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
                .clickable(enabled = canSpin, onClickLabel = "Spin the wheel", onClick = onSpin)
                .testTag("spin-hub"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (spin != null) "…" else "SPIN",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
            )
        }
    }
}
