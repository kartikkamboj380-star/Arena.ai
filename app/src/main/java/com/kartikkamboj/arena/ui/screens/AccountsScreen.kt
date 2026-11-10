package com.kartikkamboj.arena.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kartikkamboj.arena.core.relativeAge
import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.ArenaAccount
import com.kartikkamboj.arena.data.repo.SessionIsolation
import com.kartikkamboj.arena.di.AppContainer
import com.kartikkamboj.arena.domain.AccountPolicy
import com.kartikkamboj.arena.ui.ArenaDestination
import com.kartikkamboj.arena.ui.components.ArenaBackdrop
import com.kartikkamboj.arena.ui.components.ArenaCard
import com.kartikkamboj.arena.ui.components.GhostButton
import com.kartikkamboj.arena.ui.components.InfoChip
import com.kartikkamboj.arena.ui.components.MetricCell
import com.kartikkamboj.arena.ui.components.PrimaryButton
import com.kartikkamboj.arena.ui.components.SectionTitle
import com.kartikkamboj.arena.ui.components.StatusPill
import com.kartikkamboj.arena.ui.theme.ArenaPalette
import com.kartikkamboj.arena.ui.theme.LocalDarkTheme
import com.kartikkamboj.arena.web.ArenaUrls
import kotlinx.coroutines.launch

/**
 * The Account Manager: five slots, each with its own login status, its own session material and its
 * own controls.
 *
 * The screen is explicit about the one thing the platform cannot do — see [SessionIsolation]: only
 * one slot owns the shared cookie jar at a time, so "connected" for two slots means "each has a
 * stored session that is restored when you select it", not "both are live simultaneously". Saying
 * anything else would be a lie about the product, and the card copy below refuses to.
 */
@Composable
fun AccountsScreen(container: AppContainer, navigator: NavHostController) {
    val registry by container.accounts.registry.collectAsStateWithLifecycle()
    val online by container.network.available.collectAsStateWithLifecycle()
    // Recomputed per composition: "last active" should never be a frozen timestamp.
    val now = container.clock.nowMillis()
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var slotToDelete by remember { mutableStateOf<Int?>(null) }
    var slotToRename by remember { mutableStateOf<Int?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val accounts = registry.accounts
    val connected = accounts.count { it.isAuthenticated }
    val stored = accounts.count { it.hasStoredSession && !it.isAuthenticated }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            ArenaBackdrop()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 0.dp,
                    end = 0.dp,
                    top = 6.dp,
                    bottom = 28.dp,
                ),
            ) {
                item {
                    SectionTitle(
                        title = "Accounts",
                        subtitle = "Up to ${AccountPolicy.MAX_ACCOUNTS} profiles · each with its own session",
                        action = {
                            GhostButton(
                                text = if (container.accounts.isFull()) "Full" else "Add slot",
                                icon = Icons.Filled.Add,
                                enabled = !container.accounts.isFull(),
                                onClick = { showAdd = true },
                            )
                        },
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricCell(
                            label = "Verified",
                            value = "$connected/${AccountPolicy.MAX_ACCOUNTS}",
                            modifier = Modifier.weight(1f),
                            tint = if (connected > 0) ArenaPalette.Mint else null,
                        )
                        MetricCell(
                            label = "Selected",
                            value = registry.selectedSlot?.let { "Account $it" } ?: "none",
                            modifier = Modifier.weight(1f),
                            tint = if (registry.selectedSlot == null) ArenaPalette.Amber else null,
                        )
                        MetricCell(
                            label = "Network",
                            value = if (online) "online" else "offline",
                            modifier = Modifier.weight(1f),
                            tint = if (online) ArenaPalette.Mint else ArenaPalette.Rose,
                        )
                    }
                }

                item {
                    IsolationExplainer(
                        isolation = container.isolation,
                        storedUnverified = stored,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }

                (1..AccountPolicy.MAX_ACCOUNTS).forEach { slot ->
                    val account = accounts.firstOrNull { it.slot == slot }
                    item(key = "slot-$slot") {
                        if (account == null) {
                            FreeSlotCard(
                                slot = slot,
                                enabled = accounts.size < AccountPolicy.MAX_ACCOUNTS,
                                onCreate = { showAdd = true },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        } else {
                            AccountCard(
                                container = container,
                                account = account,
                                selected = registry.selectedSlot == slot,
                                now = now,
                                online = online,
                                onSelect = { container.session.switchTo(slot) },
                                onLogin = { container.session.reconnect(slot) },
                                onVerify = {
                                    container.session.probeNow()
                                    feedback = "Re-checking slot $slot against the loaded page…"
                                },
                                onLogout = { container.session.logout(slot) },
                                onRemove = { slotToDelete = slot },
                                onRename = { slotToRename = slot },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                item {
                    ArenaCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "How this app handles sign-in",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(8.dp))
                        listOf(
                            "There is no Arena API for third-party clients, so sign-in happens on " +
                                "arena.ai itself, inside the slot's own session.",
                            "This app never shows a login form and never reads one. Passwords are only " +
                                "ever typed into Arena's page.",
                            "Cookie jars are stored per slot, encrypted with an Android Keystore key, " +
                                "and swapped when you switch — never merged.",
                            "Google and other identity providers refuse embedded views. The app hands " +
                                "those to your browser instead of trying to defeat the block, which " +
                                "means a browser sign-in cannot be imported back.",
                            "If the service does not allow five simultaneous sessions, the stored jars " +
                                "still work one at a time; nothing here pretends otherwise.",
                        ).forEach { line ->
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "·",
                                    color = ArenaPalette.Emerald,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.width(12.dp),
                                )
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        GhostButton(
                            text = "Read the authentication notes",
                            onClick = { navigator.navigate(ArenaDestination.SETTINGS.route) },
                        )
                    }
                }

                feedback?.let { message ->
                    item {
                        AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelMedium,
                                color = ArenaPalette.Amber,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddSlotDialog(
            nextSlot = AccountPolicy.firstFreeSlot(accounts),
            onDismiss = { showAdd = false },
            onCreate = { label ->
                scope.launch {
                    when (val outcome = container.accounts.add(label)) {
                        is AccountPolicy.AddOutcome.Created -> {
                            showAdd = false
                            feedback = "Slot ${outcome.slot} created. Select it to start a session."
                        }

                        is AccountPolicy.AddOutcome.Full -> feedback =
                            "All ${AccountPolicy.MAX_ACCOUNTS} slots are in use. Remove one first."

                        is AccountPolicy.AddOutcome.InvalidLabel -> feedback = outcome.reason
                    }
                }
            },
        )
    }

    slotToDelete?.let { slot ->
        val target = accounts.firstOrNull { it.slot == slot }
        AlertDialog(
            onDismissRequest = { slotToDelete = null },
            title = { Text("Remove ${target?.shortName ?: "slot $slot"}?") },
            text = {
                Text(
                    "Deletes this slot's stored cookies, its Android web-storage profile if present, " +
                        "and its local thread pointers. Arena's own account and history are untouched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.accounts.remove(slot)
                        container.rateLimits.clear(slot)
                        slotToDelete = null
                        feedback = "Slot $slot removed. No slot is selected automatically — choose one."
                    }
                }) { Text("Remove", color = ArenaPalette.Rose) }
            },
            dismissButton = { TextButton(onClick = { slotToDelete = null }) { Text("Keep") } },
        )
    }

    slotToRename?.let { slot ->
        RenameDialog(
            current = accounts.firstOrNull { it.slot == slot }?.label.orEmpty(),
            onDismiss = { slotToRename = null },
            onSubmit = { next ->
                scope.launch {
                    val invalid = container.accounts.rename(slot, next)
                    feedback = invalid ?: "Slot $slot renamed."
                    if (invalid == null) slotToRename = null
                }
            },
        )
    }
}

@Composable
private fun IsolationExplainer(isolation: SessionIsolation, storedUnverified: Int, modifier: Modifier = Modifier) {
    val capability = isolation.capability
    ArenaCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        if (capability.multiProfileSupported) ArenaPalette.Mint.copy(alpha = 0.16f)
                        else ArenaPalette.Amber.copy(alpha = 0.16f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = null,
                    tint = if (capability.multiProfileSupported) ArenaPalette.Mint else ArenaPalette.Amber,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Session isolation on this device",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = capability.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Only one slot is live in the web view at a time. Selecting another slot stores " +
                "the current jar, wipes it, and installs that slot's own — no cookie is ever shared " +
                "between accounts." +
                if (storedUnverified > 0) {
                    " $storedUnverified slot(s) hold a stored session that has not been verified yet; " +
                        "they are shown as unverified, not as connected."
                } else {
                    ""
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FreeSlotCard(slot: Int, enabled: Boolean, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)), shape)
            .background(
                if (LocalDarkTheme.current) Color(0x0FFFFFFF) else Color(0x08000000),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$slot",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
                .padding(top = 4.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Slot $slot is free",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (enabled) "Create a profile to keep one more separate Arena session."
                else "All ${AccountPolicy.MAX_ACCOUNTS} slots are in use — remove one to free a slot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (enabled) {
            GhostButton(text = "Create", icon = Icons.Filled.Add, onClick = onCreate)
        }
    }
}

@Composable
private fun AccountCard(
    container: AppContainer,
    account: ArenaAccount,
    selected: Boolean,
    now: Long,
    online: Boolean,
    onSelect: () -> Unit,
    onLogin: () -> Unit,
    onVerify: () -> Unit,
    onLogout: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ringAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "selection-ring",
    )
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) ArenaPalette.SurfaceHigh else ArenaPalette.Surface)
            .border(
                BorderStroke(
                    width = if (selected) 1.4.dp else 1.dp,
                    color = if (selected) ArenaPalette.Emerald.copy(alpha = 0.35f + 0.35f * ringAlpha)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                ),
                shape,
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) Brush.linearGradient(listOf(ArenaPalette.Emerald, ArenaPalette.Amber))
                        else Brush.linearGradient(listOf(ArenaPalette.SurfaceVariant, ArenaPalette.SurfaceVariant)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${account.slot}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) ArenaPalette.OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.shortName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = account.emailHint ?: account.observedLabel ?: "no identity recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable { expanded = !expanded }
                    .padding(2.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(account.status)
            if (selected) {
                InfoChip(label = "In use now", leadingIcon = Icons.Filled.Check, selected = true)
            }
            if (!online) {
                InfoChip(label = "No network", leadingIcon = Icons.Filled.Refresh)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell(
                label = "Last active",
                value = relativeAge(account.lastActiveAtMillis, now),
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                label = "Cookies",
                value = "${account.cookieCount}",
                modifier = Modifier.weight(1f),
                tint = when {
                    account.cookieCount == 0 -> ArenaPalette.TextFaint
                    account.isAuthenticated -> ArenaPalette.Mint
                    else -> ArenaPalette.Amber
                },
            )
            MetricCell(
                label = "Probe",
                value = if (account.lastProbeAtMillis > 0L) relativeAge(account.lastProbeAtMillis, now) else "never",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected) {
                PrimaryButton(text = "Selected", onClick = onSelect, enabled = false, modifier = Modifier.weight(1f))
            } else {
                PrimaryButton(text = "Select", onClick = onSelect, modifier = Modifier.weight(1f))
            }
            GhostButton(
                text = if (account.isAuthenticated) "Recheck" else "Log in",
                icon = if (account.isAuthenticated) Icons.Filled.Refresh else Icons.Filled.Key,
                onClick = if (account.isAuthenticated) onVerify else onLogin,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "Log out",
                onClick = onLogout,
                enabled = account.hasStoredSession || account.isAuthenticated,
            )
            GhostButton(text = "Rename", onClick = onRename)
            Box(modifier = Modifier.weight(1f))
            GhostButton(text = "Remove", icon = Icons.Filled.Delete, destructive = true, onClick = onRemove)
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(tween(180)),
            exit = fadeOut() + shrinkVertically(tween(140)),
        ) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                DetailLine("Identifier", "Account ${account.slot} · ${account.jarKey}")
                DetailLine("Login status", account.status.name.lowercase().replace('_', ' '))
                DetailLine(
                    "Connection status",
                    when {
                        !online -> "offline"
                        account.status == AccountStatus.CONNECTION_ERROR -> "error"
                        else -> "reachable (https only)"
                    },
                )
                DetailLine(
                    "Session status",
                    when {
                        account.cookieCount == 0 -> "no stored jar"
                        account.isAuthenticated -> "${account.cookieCount} cookie(s) · verified"
                        else -> "${account.cookieCount} cookie(s) · unverified"
                    },
                )
                DetailLine(
                    "Jar age",
                    if (account.snapshotCreatedAtMillis > 0L) relativeAge(account.snapshotCreatedAtMillis, now) else "n/a",
                )
                DetailLine("Interactions tracked", account.interactionCount.toString())
                DetailLine("Origin", ArenaUrls.COOKIE_URL)
                Spacer(Modifier.height(10.dp))
                NoteField(
                    value = account.note,
                    onSave = { text ->
                        scope.launch { container.accounts.setNote(account.slot, text) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                NoteField(
                    value = account.emailHint.orEmpty(),
                    placeholder = "Optional label, e.g. work e-mail (stored locally, never sent anywhere)",
                    onSave = { text -> scope.launch { container.accounts.setEmailHint(account.slot, text) } },
                )
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NoteField(value: String, onSave: (String) -> Unit, placeholder: String = "Personal note") {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            minLines = 1,
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { onSave(text) }) { Text("Save") }
    }
}

@Composable
private fun AddSlotDialog(nextSlot: Int?, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create account slot ${nextSlot ?: ""}") },
        text = {
            Column {
                Text(
                    text = "A slot is a local identity: its own name, its own stored cookies, its own " +
                        "history. Nothing is copied from another slot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text(AccountPolicy.defaultLabel(nextSlot ?: 1)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(label.ifBlank { AccountPolicy.defaultLabel(nextSlot ?: 1) }) }) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var label by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename slot") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(label) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
