package com.movieroulettetuesday.app.suggest

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.movieroulettetuesday.app.catalog.RouletteCategory
import kotlinx.coroutines.launch

object SuggestionMail {
    /** ACTION_SENDTO mailto: with extras too (some mail apps ignore the URI query). Returns false when no mail app. */
    fun open(context: Context, email: MovieSuggestion): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, email.mailtoUri.toUri())
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(MovieSuggestion.ADDRESS))
            .putExtra(Intent.EXTRA_SUBJECT, email.subject)
            .putExtra(Intent.EXTRA_TEXT, email.body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun copyAddress(context: Context) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Email", MovieSuggestion.ADDRESS))
    }
}

/**
 * "Suggest a Movie" (iOS SuggestMovieSheet). Email is always available. When the build has a
 * community relay ([sendInApp] != null) the primary action sends a CloudKit `Suggestion` through it,
 * rate-limited to one per 10 minutes, and email becomes the fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestMovieSheet(
    categories: List<RouletteCategory>,
    onDismiss: () -> Unit,
    sendInApp: (suspend (InAppSuggestion) -> String?)? = null,
    rateLimit: () -> SuggestionRateLimit = { SuggestionRateLimit() },
    recordSend: () -> Unit = {},
    now: () -> Long = System::currentTimeMillis,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(InAppSuggestion("")) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var categoryOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("suggest-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Suggest a Movie", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("What should the hosts spin?", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(draft.title, { draft = draft.copy(title = InAppSuggestion.capped(it, InAppSuggestion.TITLE_LIMIT)) },
                label = { Text("Movie title") }, placeholder = { Text("Tremors (1990)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("suggest-title"))
            Box {
                OutlinedButton({ categoryOpen = true }, Modifier.fillMaxWidth().testTag("suggest-category")) {
                    Text("Category: " + (draft.category ?: "None"))
                }
                DropdownMenu(categoryOpen, { categoryOpen = false }) {
                    DropdownMenuItem({ Text("None") }, { draft = draft.copy(category = null); categoryOpen = false })
                    categories.forEach { c ->
                        DropdownMenuItem({ Text(c.displayName) }, { draft = draft.copy(category = c.displayName); categoryOpen = false },
                            modifier = Modifier.testTag("suggest-category-${c.id}"))
                    }
                }
            }
            OutlinedTextField(draft.note, { draft = draft.copy(note = InAppSuggestion.capped(it, InAppSuggestion.NOTE_LIMIT)) },
                label = { Text("Why (optional)") }, placeholder = { Text("Why it belongs on the wheel") }, minLines = 3, maxLines = 6,
                supportingText = { Text("${maxOf(draft.noteCharactersLeft, 0)} characters left") },
                modifier = Modifier.fillMaxWidth().testTag("suggest-why"))
            OutlinedTextField(draft.creditName, { draft = draft.copy(creditName = InAppSuggestion.capped(it, InAppSuggestion.NAME_LIMIT)) },
                label = { Text("Credit me as (optional)") }, placeholder = { Text("e.g. “Laura from Ohio”") }, singleLine = true,
                supportingText = { Text("Only what you type here is sent.") },
                modifier = Modifier.fillMaxWidth().testTag("suggest-credit"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("suggest-error")) }

            fun sendEmail() {
                if (SuggestionMail.open(context, draft.asEmail)) onDismiss() else { SuggestionMail.copyAddress(context); copied = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onDismiss) { Text("Cancel") }
                if (sendInApp != null) {
                    OutlinedButton(::sendEmail, enabled = draft.isValid, modifier = Modifier.testTag("suggest-email")) { Text("Send by email instead") }
                    Button(
                        onClick = {
                            val limit = rateLimit()
                            val wait = limit.secondsRemaining(now())
                            if (wait != null) { error = SuggestionRateLimit.message(wait); return@Button }
                            sending = true
                            error = null
                            scope.launch {
                                val problem = sendInApp(draft)
                                sending = false
                                if (problem == null) { recordSend(); sent = true } else error = problem
                            }
                        },
                        enabled = draft.isValid && !sending,
                        modifier = Modifier.testTag("suggest-send"),
                    ) { if (sending) CircularProgressIndicator(Modifier.padding(2.dp)) else Text("Send") }
                } else {
                    Button(::sendEmail, enabled = draft.isValid, modifier = Modifier.testTag("suggest-email")) { Text("Send by email") }
                }
            }
            Text("Email goes to ${MovieSuggestion.ADDRESS} from your own mail app.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (sent) {
        AlertDialog({ onDismiss() }, confirmButton = { TextButton(onDismiss) { Text("OK") } },
            title = { Text("Thanks! Suggestion sent.") }, text = { Text("The hosts will see your pick.") })
    }
    if (copied) {
        AlertDialog({ onDismiss() }, confirmButton = { TextButton(onDismiss) { Text("OK") } },
            title = { Text("Email address copied") },
            text = { Text("No mail app is set up, so we copied ${MovieSuggestion.ADDRESS}. Paste it into any email app to send your pick.") })
    }
}
