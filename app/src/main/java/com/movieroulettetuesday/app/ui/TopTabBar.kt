package com.movieroulettetuesday.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * iPadOS 18 top tab bar (iOS `TabView` at regular width): a floating capsule centred at the top
 * with the four tabs as text pills; the selected tab is a solid primary pill, the others quiet
 * (onSurfaceVariant). Compact width keeps the bottom `NavigationBar`, like the iPhone tab bar.
 */
@Composable
fun TopTabBar(
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    newEpisodes: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 6.dp)
            .testTag("nav-top-tabs"),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Floating "chrome" capsule: the highest container tone, a lifted shadow and a hairline
        // edge, like the iPadOS 18 tab bar's material over content.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Row(Modifier.padding(3.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TopLevelDestination.entries.forEach { dest ->
                    val selected = isSelected(dest)
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .selectable(selected, role = Role.Tab, onClick = { onSelect(dest) })
                            .heightIn(min = 36.dp)
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                            .testTag("tab-${dest.route}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(dest.label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (dest == TopLevelDestination.Episodes && newEpisodes > 0) {
                            Badge(Modifier.testTag("episodes-badge")) { Text("$newEpisodes") }
                        }
                    }
                }
            }
        }
    }
}
