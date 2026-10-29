package com.movieroulettetuesday.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Two-pane list/detail for regular width (iOS `NavigationSplitView`, list column 320...460 dp,
 * ideal 380). The detail pane is empty until something is picked, like iOS's
 * `ContentUnavailableView`.
 */
@Composable
fun ListDetailPane(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("list-detail")) {
        val listWidth = ListDetailLayout.listWidth(maxWidth.value.toDouble()).dp
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight().testTag("list-pane")) { list() }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight().testTag("detail-pane")) { (detail ?: empty)() }
        }
    }
}

/**
 * The detail pane's own stack (iOS: a `NavigationStack` in the split view's detail column). Links
 * from the detail push a page that fills the pane while the list stays beside it; the page's Back
 * button or the system back gesture pops one page. Each level keeps its saveable state (scroll
 * position) while covered.
 */
@Composable
fun <T : Any> PaneStack(
    stack: SnapshotStateList<T>,
    root: @Composable () -> Unit,
    page: @Composable (page: T, pop: () -> Unit) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty(), onBack = pop)
    val depth = stack.size
    Box(Modifier.fillMaxSize().testTag("pane-page-$depth")) {
        if (depth == 0) {
            holder.SaveableStateProvider("root") { root() }
        } else {
            val top = stack[depth - 1]
            holder.SaveableStateProvider("page-$depth-$top") { page(top, pop) }
        }
    }
}

/** "Pick an Episode" / "Pick a Movie" placeholder for the empty detail pane. */
@Composable
fun EmptyDetail(title: String, icon: ImageVector, description: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp).testTag("detail-empty"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Selected row in a list pane: tinted, and reported as selected to accessibility services. */
fun Modifier.selectedRow(selected: Boolean, tint: Color): Modifier =
    semantics { this.selected = selected }
        .then(if (selected) Modifier.padding(horizontal = 6.dp).background(tint, RoundedCornerShape(12.dp)) else Modifier)
