package com.kartikkamboj.arena.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kartikkamboj.arena.core.relativeAge
import com.kartikkamboj.arena.data.model.ThreadRecord
import com.kartikkamboj.arena.data.model.ThreadSource
import com.kartikkamboj.arena.di.AppContainer
import com.kartikkamboj.arena.ui.ArenaDestination
import com.kartikkamboj.arena.ui.components.ArenaCard
import com.kartikkamboj.arena.ui.components.EmptyState
import com.kartikkamboj.arena.ui.components.GhostButton
import com.kartikkamboj.arena.ui.components.MarkdownText
import com.kartikkamboj.arena.ui.components.InfoChip
import com.kartikkamboj.arena.ui.components.SectionTitle
import com.kartikkamboj.arena.ui.components.StatusPill
import com.kartikkamboj.arena.ui.theme.ArenaPalette
import com.kartikkamboj.arena.web.ArenaUrls
import kotlinx.coroutines.launch

/**
 * Local ledger of sessions this client opened, plus the titles the user chose to import from their
 * own Arena history page.
 *
 * The transcript is not mirrored here — there is no API that would allow it, and pretending
 * otherwise would mean a history screen full of nothing. Each row is a pointer that jumps back into
 * the right slot's session, with the user's own note attached.
 */
@Composable
fun HistoryScreen(container: AppContainer, navigator: NavHostController) {
    val threads by container.history.threads.collectAsStateWithLifecycle()
    val registry by container.accounts.registry.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()
    val running by container.session.extractionRunning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var filterSlot by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<ThreadRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val visible = remember(threads, filterSlot) {
        if (filterSlot == null) threads else threads.filter { it.slot == filterSlot }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
    ) {
        item {
            SectionTitle(
                title = "History",
                subtitle = "Sessions this app opened · Arena keeps the actual transcripts",
                action = {
                    if (threads.isNotEmpty()) {
                        GhostButton(text = "Clear", icon = Icons.Filled.Delete, destructive = true, onClick = { confirmClear = true })
                    }
                },
            )
        }

        item {
            ArenaCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Import reads only the links visible on arena.ai/history in the Chat tab, and " +
                        "only when you press the button. Nothing is fetched in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(
                        text = if (running) "Reading…" else "Import visible page",
                        icon = Icons.Filled.Refresh,
                        enabled = !running && ArenaUrls.isArenaUrl(session.url),
                        onClick = { container.session.importVisibleHistory() },
                    )
                    GhostButton(
                        text = "Open Arena history",
                        icon = Icons.Filled.OpenInNew,
                        onClick = {
                            container.session.loadUrlInSession(ArenaUrls.HISTORY)
                            navigator.navigate(ArenaDestination.CHAT.route) { launchSingleTop = true }
                        },
                    )
                }
            }
        }

        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count = registry.accounts.size + 1) { index ->
                    if (index == 0) {
                        InfoChip(
                            label = "All (${threads.size})",
                            selected = filterSlot == null,
                            onClick = { filterSlot = null },
                        )
                    } else {
                        val account = registry.accounts[index - 1]
                        InfoChip(
                            label = "${account.shortName} (${threads.count { it.slot == account.slot }})",
                            selected = filterSlot == account.slot,
                            leadingIcon = Icons.Filled.History,
                            onClick = { filterSlot = account.slot },
                        )
                    }
                }
            }
        }

        if (running) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    color = ArenaPalette.Cyan,
                    trackColor = Color.Transparent,
                )
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "No local records",
                    body = "Open a chat and press “Track page” in the toolbar, or import the titles from " +
                        "your Arena history page. Records are per account slot, so switching slots never " +
                        "mixes them up.",
                    actionLabel = "Go to Chat",
                    onAction = { navigator.navigate(ArenaDestination.CHAT.route) { launchSingleTop = true } },
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        visible.forEach { record ->
            item(key = record.id) {
                ThreadRow(
                    record = record,
                    accountLabel = registry.accounts.firstOrNull { it.slot == record.slot }?.shortName
                        ?: "Slot ${record.slot}",
                    accountStatus = registry.accounts.firstOrNull { it.slot == record.slot }?.status,
                    onOpen = {
                        val url = record.url
                        if (url != null) {
                            if (registry.selectedSlot != record.slot) {
                                container.session.switchTo(record.slot)
                                container.session.loadUrlInSession(url)
                            } else {
                                container.session.loadUrlInSession(url)
                            }
                            navigator.navigate(ArenaDestination.CHAT.route) { launchSingleTop = true }
                        } else {
                            container.session.reportProblem(
                                "This record has no link — it was a mode session. Open the mode from Models.",
                            )
                        }
                    },
                    onEdit = { editing = record },
                    onPin = { scope.launch { container.history.togglePin(record.id) } },
                    onDelete = { scope.launch { container.history.remove(record.id) } },
                )
            }
        }
    }

    editing?.let { record ->
        NoteDialog(
            record = record,
            onDismiss = { editing = null },
            onSave = { title, note ->
                scope.launch {
                    container.history.setTitle(record.id, title)
                    container.history.setNote(record.id, note)
                    editing = null
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear local records?") },
            text = {
                Text(
                    "Removes ${threads.size} pointer(s) this app stored. Arena's own history is not " +
                        "touched, and no sign-in is affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.history.clearAll()
                        confirmClear = false
                    }
                }) { Text("Clear", color = ArenaPalette.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ThreadRow(
    record: ThreadRecord,
    accountLabel: String,
    accountStatus: com.kartikkamboj.arena.data.model.AccountStatus?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    ArenaCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (record.pinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint = ArenaPalette.Amber,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoChip(label = accountLabel, leadingIcon = Icons.Filled.History)
            accountStatus?.let { StatusPill(it) }
            Text(
                text = relativeAge(record.lastOpenedAtMillis, System.currentTimeMillis()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when (record.source) {
                    ThreadSource.LOCAL -> "opened here"
                    ThreadSource.IMPORTED_FROM_ARENA -> "imported from your history page"
                } + " · ${record.mode.label}" + (record.model?.let { " · ${it.displayName}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (record.note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (record.note.isNotBlank()) ArenaPalette.SurfaceVariant.copy(alpha = 0.6f) else Color.Transparent,
                    )
                    .padding(10.dp),
            ) {
                MarkdownText(source = record.note)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(text = "Open", icon = Icons.Filled.OpenInNew, onClick = onOpen)
            GhostButton(text = "Edit", onClick = onEdit)
            GhostButton(text = if (record.pinned) "Unpin" else "Pin", icon = Icons.Filled.PushPin, onClick = onPin)
            Box(modifier = Modifier.weight(1f))
            GhostButton(text = "Delete", icon = Icons.Filled.Delete, destructive = true, onClick = onDelete)
        }
    }
}

@Composable
private fun NoteDialog(
    record: ThreadRecord,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf(record.title) }
    var note by remember { mutableStateOf(record.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session note") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note · markdown supported") },
                    placeholder = {
                        Text(
                            "**Follow-up** · ask again with shorter examples\n- compare two models",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Stored on this device only, for this slot. Nothing here is sent to Arena.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
