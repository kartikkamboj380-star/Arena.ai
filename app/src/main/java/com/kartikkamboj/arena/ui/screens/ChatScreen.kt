package com.kartikkamboj.arena.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.FrameLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.ArenaAccount
import com.kartikkamboj.arena.data.model.ProjectContext
import com.kartikkamboj.arena.data.model.SessionState
import com.kartikkamboj.arena.di.AppContainer
import com.kartikkamboj.arena.domain.ProjectPolicy
import com.kartikkamboj.arena.ui.ArenaDestination
import com.kartikkamboj.arena.ui.components.ArenaCard
import com.kartikkamboj.arena.ui.components.EmptyState
import com.kartikkamboj.arena.ui.components.ErrorCard
import com.kartikkamboj.arena.ui.components.GhostButton
import com.kartikkamboj.arena.ui.components.InfoChip
import com.kartikkamboj.arena.ui.components.PrimaryButton
import com.kartikkamboj.arena.ui.components.StatusPill
import com.kartikkamboj.arena.ui.theme.ArenaPalette
import com.kartikkamboj.arena.web.ArenaUrls

/**
 * The chat surface: Arena's own interface, running inside the selected slot's isolated session,
 * wrapped in the native chrome a web page cannot provide — account identity, connection state,
 * rate-limit explanation, loading, and recovery actions.
 *
 * The native composer is opt-in (Settings) and only ever *places* text into the page's own input
 * field. Nothing here fabricates a conversation: this client has no read access to Arena's
 * transcripts, so answers are rendered by Arena, and the app never restates them.
 */
@Composable
fun ChatScreen(container: AppContainer, navigator: NavHostController) {
    val session by container.session.state.collectAsStateWithLifecycle()
    val registry by container.accounts.registry.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val catalog by container.models.catalog.collectAsStateWithLifecycle()
    val online by container.network.available.collectAsStateWithLifecycle()
    val account = registry.accounts.firstOrNull { it.slot == registry.selectedSlot }
    val modelLabel = catalog.activeSelection()?.displayName

    LaunchedEffect(registry.selectedSlot) {
        val slot = registry.selectedSlot ?: return@LaunchedEffect
        container.session.openFor(slot)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ChatHeader(
            container = container,
            account = account,
            modelLabel = modelLabel,
            session = session,
            online = online,
            onOpenAccounts = { navigator.navigate(ArenaDestination.ACCOUNTS.route) },
            onOpenModels = { navigator.navigate(ArenaDestination.MODELS.route) },
        )

        ProjectStrip(
            container = container,
            onOpenProjects = { navigator.navigate(ArenaDestination.PROJECTS.route) { launchSingleTop = true } },
        )

        AnimatedVisibility(
            visible = session.loading,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(180)),
        ) {
            LinearProgressIndicator(
                progress = { (session.progress / 100f).coerceIn(0.02f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = ArenaPalette.Emerald,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
        }

        session.error?.let { error ->
            ErrorCard(
                error = error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                detail = error.detail,
                onAction = if (error.retryable) {
                    {
                        if (!online) {
                            container.session.reportProblem("Still offline — reconnect Wi-Fi or mobile data first.")
                        } else {
                            container.session.reload()
                        }
                    }
                } else {
                    null
                },
            )
        }

        if (account == null) {
            EmptyState(
                icon = Icons.Filled.Key,
                title = "No account slot selected",
                body = "Create up to five slots and pick one. Each slot keeps its own Arena session, " +
                    "and this app never loads Arena until you choose one.",
                actionLabel = "Set up accounts",
                onAction = { navigator.navigate(ArenaDestination.ACCOUNTS.route) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            if (account.status == AccountStatus.LOGIN_REQUIRED || account.status == AccountStatus.SESSION_EXPIRED) {
                SignInPrompt(
                    account = account,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    onSignIn = { container.session.beginSignIn(account.slot) },
                    onBrowserSignIn = { container.session.handOffLoginToBrowser() },
                )
            }
            if (account.status == AccountStatus.RATE_LIMITED) {
                ArenaCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, ArenaPalette.Amber.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = container.rateLimits.messageFor(account = account, modelLabel = modelLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    // The alternatives are listed as they actually are: only slots this app has
                    // *verified* as connected for this device are offered, they are named, and choosing
                    // one is a tap the user makes. Nothing rotates by itself, and no count of remaining
                    // quota is implied — Arena does not report one to a client like this.
                    val alternatives = registry.accounts.filter {
                        it.slot != account.slot && it.status == AccountStatus.CONNECTED
                    }
                    Spacer(Modifier.height(10.dp))
                    if (alternatives.isEmpty()) {
                        Text(
                            text = "No other slot on this device is verified as connected right now, so " +
                                "there is nothing to hand this to. Sign in to another slot, or wait and " +
                                "retry this one — the project and this draft stay where they are either " +
                                "way.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "Verified and available on this device:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            alternatives.forEach { other ->
                                InfoChip(
                                    label = other.shortName,
                                    leadingIcon = Icons.Filled.Person,
                                    onClick = { container.session.switchTo(other.slot) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton(
                        text = "Pick another account",
                        icon = Icons.Filled.Person,
                        onClick = { navigator.navigate(ArenaDestination.ACCOUNTS.route) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (session.authenticated) ArenaPalette.SurfaceHigh else ArenaPalette.SurfaceLow),
            ) {
                AndroidView(
                    factory = { context -> FrameLayout(context) },
                    // Attaching in `update` keeps a single WebView instance owned by the
                    // coordinator: leaving this tab detaches it, it is never destroyed here.
                    update = { frame -> if (frame.parent != null) container.session.attach(frame) },
                    onRelease = { frame -> container.session.detach(frame) },
                    modifier = Modifier.fillMaxSize(),
                )

                if (session.loading) {
                    // A plain conditional instead of AnimatedVisibility: this overlay sits in a Box
                    // scope, where the ColumnScope overload is not callable, and a fade adds nothing
                    // a spinner does not already communicate.
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = ArenaPalette.Amber,
                            trackColor = Color.Transparent,
                        )
                    }
                }

                FloatingActionButton(
                    onClick = { container.session.scrollToBottom() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    shape = CircleShape,
                    containerColor = ArenaPalette.SurfaceVariant.copy(alpha = 0.94f),
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "Scroll to bottom",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            ChatToolbar(container = container, session = session)

            if (settings.nativeComposerEnabled) {
                DraftBar(
                    container = container,
                    enabled = container.session.canInteract,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatHeader(
    container: AppContainer,
    account: ArenaAccount?,
    modelLabel: String?,
    session: SessionState,
    online: Boolean,
    onOpenAccounts: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val registry by container.accounts.registry.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(ArenaPalette.Emerald, ArenaPalette.Amber))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "A",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ArenaPalette.OnAccent,
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Arena AI",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "AI Chat Experience",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                InfoChip(
                    label = account?.shortName ?: "No account",
                    leadingIcon = Icons.Filled.Person,
                    selected = account?.isAuthenticated == true,
                    onClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    registry.accounts.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.shortName, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(8.dp))
                                    StatusPill(entry.status)
                                }
                            },
                            onClick = {
                                menuOpen = false
                                if (entry.slot != registry.selectedSlot) container.session.switchTo(entry.slot)
                            },
                            trailingIcon = if (entry.slot == registry.selectedSlot) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Manage accounts", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuOpen = false
                            onOpenAccounts()
                        },
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(account?.status ?: AccountStatus.NOT_CONNECTED)
            InfoChip(
                label = modelLabel?.let { "Model · $it" } ?: "Model · Auto",
                leadingIcon = Icons.Filled.Memory,
                onClick = onOpenModels,
            )
            InfoChip(
                label = if (online) "Online" else "Offline",
                leadingIcon = Icons.Filled.Refresh,
                selected = online,
            )
            InfoChip(
                label = if (session.authenticated) "Session verified" else "Session unverified",
                leadingIcon = Icons.Filled.Key,
                selected = session.authenticated,
            )
        }
    }
}

@Composable
private fun SignInPrompt(
    account: ArenaAccount,
    modifier: Modifier = Modifier,
    onSignIn: () -> Unit,
    onBrowserSignIn: () -> Unit,
) {
    ArenaCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Key,
                contentDescription = null,
                tint = ArenaPalette.Amber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = when (account.status) {
                    AccountStatus.SESSION_EXPIRED -> "Session expired · ${account.shortName}"
                    else -> "Sign in required · ${account.shortName}"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sign in on Arena's own page inside this slot's session. This app has no login " +
                "form, so it never sees what you type. If your provider refuses the in-app view, " +
                "use the browser option — that session will not be shared back into this slot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(text = "Open sign-in", onClick = onSignIn, leadingIcon = Icons.Filled.Add)
            GhostButton(text = "Use browser", onClick = onBrowserSignIn)
        }
    }
}

/**
 * The project line: which workspace is open, whether Arena's own conversation can be continued from
 * the account on screen, and how much local history belongs to it. Tapping it opens the workspace —
 * switching accounts never moves this line, which is the whole point of the feature.
 */
@Composable
private fun ProjectStrip(container: AppContainer, onOpenProjects: () -> Unit) {
    val registry by container.projects.registry.collectAsStateWithLifecycle()
    val accounts by container.accounts.registry.collectAsStateWithLifecycle()
    val threads by container.history.threads.collectAsStateWithLifecycle()
    val project = container.projects.find(registry.activeId)
    val context = if (project == null) {
        ProjectContext.LOCAL_ONLY
    } else {
        ProjectPolicy.contextOf(project, accounts.selectedSlot)
    }
    val ownedBy = project?.conversationSlot?.let { wanted ->
        accounts.accounts.firstOrNull { it.slot == wanted }?.shortName
    }
    val count = threads.count { it.projectId == project?.id }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ArenaPalette.SurfaceLow)
            .clickable(onClick = onOpenProjects)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = when (context) {
                ProjectContext.CONTINUES -> ArenaPalette.Emerald
                ProjectContext.OTHER_ACCOUNT -> ArenaPalette.Amber
                ProjectContext.LOCAL_ONLY -> ArenaPalette.TextMuted
            },
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project?.name ?: "No project — tap to create one",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Text(
                text = when (context) {
                    ProjectContext.CONTINUES -> "continues here · $count local thread(s)"
                    ProjectContext.OTHER_ACCOUNT -> "conversation belongs to ${ownedBy ?: "another account"}"
                    ProjectContext.LOCAL_ONLY -> "local workspace · $count thread(s) filed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = "Open projects",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ChatToolbar(container: AppContainer, session: SessionState) {
    val enabled = container.session.hasSessionView
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolAction(Icons.Filled.AddCircle, "New chat", enabled) {
            container.session.loadUrlInSession(ArenaUrls.CHAT)
        }
        ToolAction(Icons.Filled.Refresh, "Reload", enabled) { container.session.reload() }
        ToolAction(Icons.Filled.Stop, "Stop loading", session.loading) { container.session.stopLoading() }
        ToolAction(Icons.Filled.ContentCopy, "Copy selection", enabled) { container.session.copySelection() }
        ToolAction(Icons.Filled.ArrowDownward, "Bottom", enabled) { container.session.scrollToBottom() }
        ToolAction(Icons.Filled.Check, "Track page", enabled) { container.session.trackCurrentPage() }
    }
}

@Composable
private fun ToolAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    InfoChip(
        label = label,
        leadingIcon = icon,
        enabled = enabled,
        onClick = if (enabled) onClick else null,
    )
}

@Composable
private fun DraftBar(container: AppContainer, enabled: Boolean, modifier: Modifier = Modifier) {
    // The text is owned by the coordinator, keyed by the active project: leaving the tab, rotating
    // the device or losing the account mid-thought must not delete a prompt the user has typed.
    val registry by container.projects.registry.collectAsStateWithLifecycle()
    val projectKey = registry.activeId
    var draft by remember(projectKey) { mutableStateOf(container.session.draftFor(projectKey)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    Column(modifier = modifier) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                container.session.setDraft(projectKey, it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Draft · placed into the page, never sent by this app") },
            minLines = 1,
            maxLines = 4,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (draft.isNotBlank()) {
                        container.session.insertDraft(draft) { reason ->
                            if (reason == null) {
                                container.session.clearDraft(projectKey)
                                draft = ""
                            }
                            feedback = when (reason) {
                                null -> "Placed in Arena's composer — press send on the page."
                                "no-visible-input" -> "No visible input field on this page."
                                "disabled" -> "Turn the native draft bar on in Settings first."
                                "off-origin" -> "Drafts are only inserted into arena.ai pages."
                                else -> "The page refused the insertion${reason?.let { " ($it)" } ?: ""}."
                            }
                        }
                        focusManager.clearFocus()
                    }
                },
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ArenaPalette.Emerald,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = ArenaPalette.Amber,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = ArenaPalette.SurfaceLow.copy(alpha = 0.6f),
            ),
            shape = RoundedCornerShape(16.dp),
        )
        AnimatedVisibility(
            visible = feedback != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            feedback?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
