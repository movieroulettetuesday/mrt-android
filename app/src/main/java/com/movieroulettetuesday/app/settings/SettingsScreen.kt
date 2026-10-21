package com.movieroulettetuesday.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.catalog.HexColor
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.ProviderLogo
import com.movieroulettetuesday.app.movies.StreamingCatalog
import com.movieroulettetuesday.app.ui.ExternalLinks
import com.movieroulettetuesday.app.ui.MrtSection
import com.movieroulettetuesday.app.ui.theme.BrandColors

data class SettingsState(
    val appearance: AppearancePreference,
    val haptics: Boolean,
    val newEpisodeAlerts: Boolean,
    val alertNote: String? = null,
    /** Shown only when the community relay is configured in this build. */
    val autoShareRatings: Boolean? = null,
    val communityEnabled: Boolean = false,
    val tickSound: Boolean = true,
)

class SettingsActions(
    val setAppearance: (AppearancePreference) -> Unit,
    val setHaptics: (Boolean) -> Unit,
    val setTickSound: (Boolean) -> Unit = {},
    val setNewEpisodeAlerts: (Boolean) -> Unit,
    val setAutoShareRatings: (Boolean) -> Unit,
    val openMyServices: () -> Unit,
    val openCredits: () -> Unit,
    val suggest: () -> Unit,
    val close: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    SettingsScaffold("Settings", actions.close, modifier.testTag("settings-screen")) {
        Section("Appearance", "System follows your phone’s Light or Dark setting.") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppearancePreference.entries.forEachIndexed { i, a ->
                    SegmentedButton(state.appearance == a, { actions.setAppearance(a) }, SegmentedButtonDefaults.itemShape(i, 3),
                        Modifier.testTag("appearance-${a.raw}")) { Text(a.label) }
                }
            }
        }
        Section("Spin", "Tap the spinning wheel to stop it early.") {
            ToggleRow("Spin sound", Icons.Filled.MusicNote, state.tickSound, actions.setTickSound, "settings-tick-sound")
            ToggleRow("Haptics", Icons.Filled.Vibration, state.haptics, actions.setHaptics, "settings-haptics")
        }
        Section("Notifications", "Checks for new episodes in the background, so alerts can arrive late.") {
            ToggleRow("New-episode alert", Icons.Filled.Mail, state.newEpisodeAlerts, actions.setNewEpisodeAlerts, "settings-new-episode-alert")
            state.alertNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings-alert-note")) }
        }
        Section("Where to Watch", "Choose your streaming services to filter the Movies tab.") {
            NavRow("My services", Icons.Filled.Tv, "settings-my-services", actions.openMyServices)
        }
        state.autoShareRatings?.let { on ->
            Section("Listener Ratings", "Shares your star ratings anonymously with other listeners.") {
                ToggleRow("Auto-share my ratings", Icons.Filled.Groups, on, actions.setAutoShareRatings, "settings-auto-share")
            }
        }
        Section("Follow the Show", null) {
            FollowTheShow.links().forEachIndexed { i, link ->
                if (i > 0) HorizontalDivider()
                val icon = when (link.id) {
                    "apple" -> Icons.Filled.Headphones
                    "spotify" -> Icons.Filled.GraphicEq
                    "overcast" -> Icons.Filled.Radio
                    "youtube" -> Icons.Filled.SmartDisplay
                    "instagram" -> Icons.Filled.CameraAlt
                    "x" -> Icons.Filled.AlternateEmail
                    "facebook" -> Icons.Filled.People
                    else -> Icons.Filled.Language
                }
                Row(Modifier.fillMaxWidth().clickable { ExternalLinks.open(context, link.url) }.testTag("follow-${link.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Tile(icon, HexColor.parse(link.tileHex)?.let { Color(it.argb) } ?: BrandColors.TealDark)
                    Spacer(Modifier.width(12.dp))
                    Text(link.title, Modifier.weight(1f))
                }
            }
        }
        Section(null, "Send the hosts a pick for a future episode.") {
            NavRow("Suggest a movie for the show", Icons.Filled.Mail, "settings-suggest", actions.suggest)
        }
        Section("About", null) {
            NavRow("Credits & About", Icons.Filled.Info, "settings-credits", actions.openCredits)
        }
        Section("Privacy", null) {
            Text(PrivacyCopy.note(state.communityEnabled), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("privacy-note"))
        }
    }
}

@Composable
fun MyServicesScreen(providers: List<StreamingInfo.Provider>, selected: MyServices, onToggle: (Int) -> Unit, onBack: () -> Unit) {
    SettingsScaffold("My Services", onBack, Modifier.testTag("my-services-screen")) {
        Section(null, StreamingCatalog.ATTRIBUTION) {
            if (providers.isEmpty()) Text("Streaming info isn’t available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            providers.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider()
                val on = selected.contains(p.id)
                Row(Modifier.fillMaxWidth().clickable { onToggle(p.id) }.testTag("service-${p.id}")
                    .semantics { this.selected = on; role = Role.Checkbox }, verticalAlignment = Alignment.CenterVertically) {
                    ProviderLogo(p, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(p.name, Modifier.weight(1f))
                    if (on) Icon(Icons.Filled.Check, null, tint = BrandColors.TealLight)
                }
            }
        }
    }
}

@Composable
fun CreditsScreen(versionString: String, onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold("Credits & About", onBack, Modifier.testTag("credits-screen")) {
        Section("Streaming Data", null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TMDB", color = Color(0xFF90CEA1), fontWeight = FontWeight.Black,
                    modifier = Modifier.background(Color(0xFF0D253F), RoundedCornerShape(8.dp)).padding(10.dp, 8.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("The Movie Database (TMDB)", fontWeight = FontWeight.SemiBold)
                    Text("Streaming availability", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(StreamingCatalog.TMDB_NOTICE, Modifier.testTag("tmdb-notice"))
            Text(StreamingCatalog.ATTRIBUTION + ".")
            Text("themoviedb.org", color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { ExternalLinks.open(context, "https://www.themoviedb.org") })
        }
        Section("About", null) {
            Text("The official companion app from the hosts of Movie Roulette Tuesday: The Podcast.")
            Text("Version $versionString", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("about-version"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        }
    }
}

@Composable
private fun Section(title: String?, footer: String?, content: @Composable ColumnScope.() -> Unit) {
    Column {
        MrtSection(title, content = content)
        footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, color: Color) {
    Box(Modifier.size(28.dp).background(color, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(16.dp), tint = Color.White)
    }
}

@Composable
private fun ToggleRow(title: String, icon: ImageVector, on: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Tile(icon, BrandColors.TealDark)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f))
        Switch(on, onChange, Modifier.testTag(tag))
    }
}

@Composable
private fun NavRow(title: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Tile(icon, BrandColors.TealDark)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}
