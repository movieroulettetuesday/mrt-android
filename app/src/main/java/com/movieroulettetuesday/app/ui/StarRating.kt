package com.movieroulettetuesday.app.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.movies.HalfStars

/**
 * 0.5–5 stars in half steps (values are half-stars 1..10), iOS StarRatingView: tap a star's left
 * half for ½, right half for the whole star, or drag; tapping the current value clears it.
 * TalkBack: adjustable (setProgress) plus increase/decrease actions. Read-only when [onChange] is null.
 */
@Composable
fun StarRating(
    halfStars: Int?,
    modifier: Modifier = Modifier,
    starSize: Dp = 28.dp,
    tint: Color = Color(0xFFF39C12),
    onChange: ((Int?) -> Unit)? = null,
) {
    var drag by remember { mutableStateOf<Int?>(null) }
    val shown = drag ?: halfStars
    val interactive = if (onChange == null) Modifier else Modifier
        .pointerInput(halfStars) {
            detectTapGestures { pos ->
                val picked = HalfStars.fromLocation(pos.x.toDouble(), size.width.toDouble())
                onChange(if (picked == halfStars) null else picked)
            }
        }
        .pointerInput(halfStars) {
            detectHorizontalDragGestures(
                onDragEnd = { drag?.let(onChange); drag = null },
                onDragCancel = { drag = null },
            ) { change, _ -> drag = HalfStars.fromLocation(change.position.x.toDouble(), size.width.toDouble()) }
        }
    Row(
        modifier.then(interactive).clearAndSetSemantics {
            contentDescription = "Rating"
            stateDescription = shown?.let { "${HalfStars.label(it)} of 5 stars" } ?: "Not rated"
            if (onChange != null) {
                progressBarRangeInfo = ProgressBarRangeInfo((shown ?: 0).toFloat(), 0f..10f, steps = 9)
                setProgress { v -> onChange(HalfStars.clamped(Math.round(v))); true }
                customActions = listOf(
                    CustomAccessibilityAction("Increase rating") { onChange(HalfStars.adjusted(halfStars, true)); true },
                    CustomAccessibilityAction("Decrease rating") { onChange(HalfStars.adjusted(halfStars, false)); true },
                )
            }
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (star in 1..5) {
            val fill = HalfStars.fill(star, shown)
            Icon(
                when {
                    fill >= 1 -> Icons.Filled.Star
                    fill > 0 -> Icons.AutoMirrored.Filled.StarHalf
                    else -> Icons.Filled.StarBorder
                },
                contentDescription = null,
                tint = if (fill > 0) tint else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(starSize),
            )
        }
    }
}
