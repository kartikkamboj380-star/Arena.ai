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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kartikkamboj.arena.core.relativeAge
import com.kartikkamboj.arena.data.model.ArenaMode
import com.kartikkamboj.arena.data.model.ModelAvailability
import com.kartikkamboj.arena.data.model.ModelEntry
import com.kartikkamboj.arena.data.model.ModelSource
import com.kartikkamboj.arena.di.AppContainer
import com.kartikkamboj.arena.ui.components.ArenaCard
import com.kartikkamboj.arena.ui.components.GhostButton
import com.kartikkamboj.arena.ui.components.InfoChip
import com.kartikkamboj.arena.ui.components.MetricCell
import com.kartikkamboj.arena.ui.components.PrimaryButton
import com.kartikkamboj.arena.ui.components.SectionTitle
import com.kartikkamboj.arena.ui.theme.ArenaPalette
import com.kartikkamboj.arena.web.ArenaUrls
import kotlinx.coroutines.launch

/**
 * Model selection, honestly framed.
 *
 * Arena publishes no API for listing or choosing models, so this screen does three things and no
 * more: it exposes the *modes* the service documents (Battle, Side by Side, Direct, Agent, …), it
 * can read the ranking rows that are on screen when the user asks, and it stores the model the user
 * says they picked so the chat header can show it. An entry is never labelled as selectable here if
 * all we know is that it appeared in a leaderboard snapshot — [ModelAvailability] carries that
 * difference, and selecting an entry shows the step it still has to take inside the page.
 */
@Composable
fun ModelsScreen(container: AppContainer, navigator: NavHostController) {
    val catalog by container.models.catalog.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val running by container.session.extractionRunning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<ModelEntry?>(null) }

    val ordered = remember(catalog.models) {
        catalog.models.sortedWith(
            compareByDescending<ModelEntry> { it.pinned }.thenBy { it.rank ?: Int.MAX_VALUE },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
    ) {
        item {
            SectionTitle(
                title = "Models",
                subtitle = "Arena chooses the pair in Battle; you choose in Direct and Side by Side",
                action = {
                    GhostButton(text = "Record a model", icon = Icons.Filled.Add, onClick = { showAdd = true })
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
                    text = "No model API exists for third-party clients, so nothing here can switch a " +
                        "model for you. Pinning a model shows which one you are using in the chat " +
                        "header and keeps it visible across tabs — it never claims a change you have " +
                        "not made in Arena's own picker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCell(
                        label = "Entries",
                        value = "${catalog.models.size}",
                        modifier = Modifier.weight(1f),
                    )
                    MetricCell(
                        label = "Pinned",
                        value = "${catalog.models.count { it.pinned }}",
                        modifier = Modifier.weight(1f),
                    )
                    MetricCell(
                        label = "Last read",
                        value = relativeAge(catalog.lastRefreshAtMillis, container.clock.nowMillis()),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            SectionTitle(title = "Modes", subtitle = "Documented entry points on arena.ai")
        }

        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ArenaMode.entries.size) { index ->
                    val mode = ArenaMode.entries[index]
                    ModeCard(
                        mode = mode,
                        selected = settings.defaultMode == mode,
                        onOpen = {
                            container.settings.update { it.copy(defaultMode = mode) }
                            container.session.loadMode(mode)
                        },
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    text = if (running) "Reading page…" else "Read leaderboard rows",
                    onClick = { container.session.refreshModelList() },
                    enabled = !running && ArenaUrls.isArenaUrl(session.url),
                    leadingIcon = Icons.Filled.Refresh,
                )
                Spacer(Modifier.width(10.dp))
                GhostButton(
                    text = "Open leaderboard",
                    onClick = { container.session.loadUrlInSession(ArenaUrls.LEADERBOARD) },
                )
            }
        }

        if (ordered.isEmpty()) {
            item {
                ArenaCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "Nothing recorded yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Load arena.ai/leaderboard in the Chat tab, then press “Read " +
                            "leaderboard rows”. Rows are taken from the page you can already see; if " +
                            "the layout changes, the app reports that instead of guessing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ordered.forEach { entry ->
            item(key = entry.id) {
                ModelRow(
                    entry = entry,
                    active = catalog.activeSelectionId == entry.id,
                    onPin = { scope.launch { container.models.setPinned(entry.id, !entry.pinned) } },
                    onSelect = { pendingSelection = entry },
                    onRemove = { scope.launch { container.models.remove(entry.id) } },
                )
            }
        }
    }

    pendingSelection?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingSelection = null },
            title = { Text("Show “${entry.displayName}” as your model?") },
            text = {
                Text(
                    "This only records the label for this app: the chat header, history rows and " +
                        "rate-limit messages will name it. Arena's own picker still has to be used " +
                        "on the page — this client cannot send a model change to the service." +
                        if (entry.availability == ModelAvailability.UNAVAILABLE) {
                            "\n\nNote: this entry was not present in the most recent leaderboard read, " +
                                "so treat it as stale."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.models.select(entry.id)
                        pendingSelection = null
                    }
                }) { Text("Record it") }
            },
            dismissButton = { TextButton(onClick = { pendingSelection = null }) { Text("Cancel") } },
        )
    }

    if (showAdd) {
        AddModelDialog(
            onDismiss = { showAdd = false },
            onSubmit = { name, vendor ->
                scope.launch {
                    container.models.confirm(name, vendor, "recorded by the user")
                    showAdd = false
                }
            },
        )
    }
}

@Composable
private fun ModeCard(mode: ArenaMode, selected: Boolean, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .width(210.dp)
            .clip(shape)
            .background(if (selected) ArenaPalette.SurfaceHigh else ArenaPalette.SurfaceVariant)
            .border(
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) ArenaPalette.Emerald.copy(alpha = 0.5f) else Color.Transparent,
                ),
                shape,
            )
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Memory,
                contentDescription = null,
                tint = if (selected) ArenaPalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = mode.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = mode.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (mode.revealsModelIdentity) "model identity visible" else "identity revealed after voting",
            style = MaterialTheme.typography.labelSmall,
            color = if (mode.revealsModelIdentity) ArenaPalette.Amber else ArenaPalette.Amber,
        )
    }
}

@Composable
private fun ModelRow(
    entry: ModelEntry,
    active: Boolean,
    onPin: () -> Unit,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val tint = when (entry.availability) {
        ModelAvailability.INFORMATIONAL_ONLY -> ArenaPalette.TextMuted
        ModelAvailability.SELECTABLE_IN_WEB_UI -> ArenaPalette.Amber
        ModelAvailability.AUTOMATIC -> ArenaPalette.Emerald
        ModelAvailability.UNAVAILABLE -> ArenaPalette.Rose
    }
    ArenaCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            entry.rank?.let { rank ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$rank",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = buildString {
                        append(entry.vendor ?: "vendor not shown on the page")
                        entry.score?.let { append(" · score $it") }
                        append(" · ").append(entry.source.shortName())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                InfoChip(label = "Shown as selected", leadingIcon = Icons.Filled.Check, selected = true)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(tint.copy(alpha = 0.14f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(entry.availability.label(), style = MaterialTheme.typography.labelSmall, color = tint)
            }
            Box(modifier = Modifier.weight(1f))
            if (entry.source == ModelSource.USER_PINNED || entry.pinned) {
                GhostButton(text = "Remove", icon = Icons.Filled.Delete, destructive = true, onClick = onRemove)
            }
            GhostButton(text = if (entry.pinned) "Unpin" else "Pin", icon = Icons.Filled.Star, onClick = onPin)
            GhostButton(text = "Show as selected", onClick = onSelect)
        }
    }
}

@Composable
private fun AddModelDialog(onDismiss: () -> Unit, onSubmit: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var vendor by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record a model you picked in Arena") },
        text = {
            Column {
                Text(
                    text = "Use the exact name Arena shows in its picker. This is a local record, not a " +
                        "request to the service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Model name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = vendor,
                    onValueChange = { vendor = it },
                    label = { Text("Provider, optional") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(name, vendor.ifBlank { null }) }) { Text("Record") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun ModelSource.shortName(): String = when (this) {
    ModelSource.LEADERBOARD_LIVE -> "from leaderboard"
    ModelSource.USER_PINNED -> "recorded by you"
    ModelSource.ARENA_SELECTOR -> "Arena's picker"
}

private fun ModelAvailability.label(): String = when (this) {
    ModelAvailability.AUTOMATIC -> "chosen by Arena"
    ModelAvailability.SELECTABLE_IN_WEB_UI -> "pick it in Arena's UI"
    ModelAvailability.INFORMATIONAL_ONLY -> "leaderboard info only"
    ModelAvailability.UNAVAILABLE -> "not in latest read"
}
