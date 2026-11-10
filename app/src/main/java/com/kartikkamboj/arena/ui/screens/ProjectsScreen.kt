package com.kartikkamboj.arena.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kartikkamboj.arena.data.model.Project
import com.kartikkamboj.arena.data.model.ProjectContext
import com.kartikkamboj.arena.di.AppContainer
import com.kartikkamboj.arena.domain.ProjectPolicy
import com.kartikkamboj.arena.ui.ArenaDestination
import com.kartikkamboj.arena.ui.components.ArenaCard
import com.kartikkamboj.arena.ui.components.EmptyState
import com.kartikkamboj.arena.ui.components.GhostButton
import com.kartikkamboj.arena.ui.components.InfoChip
import com.kartikkamboj.arena.ui.components.PrimaryButton
import com.kartikkamboj.arena.ui.components.SectionTitle
import com.kartikkamboj.arena.ui.theme.ArenaPalette
import kotlinx.coroutines.launch

/**
 * The project workspace: one continuous piece of work, kept intact across account changes.
 *
 * Everything on this screen is local by design. Arena publishes no API for creating, renaming or
 * moving a conversation, so the app cannot promise to carry a transcript between accounts — what it
 * can promise, and does, is that *this* project (its name, instructions, file list, model choice and
 * the pointer back to the thread) is never reset, never auto-created and never switched behind the
 * user's back. Where the remote conversation is reachable, the status line says so in the same words
 * the policy uses; where it is not, it says that instead.
 */
@Composable
fun ProjectsScreen(container: AppContainer, navigator: NavHostController) {
    val registry by container.projects.registry.collectAsStateWithLifecycle()
    val threads by container.history.threads.collectAsStateWithLifecycle()
    val accounts by container.accounts.registry.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Project?>(null) }
    var fileFor by remember { mutableStateOf<Project?>(null) }
    var deleteFor by remember { mutableStateOf<Project?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val activeSlot = accounts.selectedSlot

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
    ) {
        item {
            SectionTitle(
                title = "Projects",
                subtitle = "One workspace · up to ${ProjectPolicy.MAX_PROJECTS} · account-independent",
                action = {
                    GhostButton(text = "New", icon = Icons.Filled.Add, onClick = { showCreate = true })
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
                    text = "Switching account slots never starts a new project, resets a conversation " +
                        "or files the work under the new account. The project keeps its id and its " +
                        "history; only the account annotation changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "What Arena cannot do: reopen an account-A conversation from account B. " +
                        "There is no API for it, so the app does not pretend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArenaPalette.Amber,
                )
            }
        }

        if (registry.projects.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Description,
                    title = "No project yet",
                    body = "Name the work you are doing — \"Android game\", \"Q3 report\" — and it " +
                        "becomes the container for instructions, files, history and whichever account " +
                        "you happen to be signed into.",
                    actionLabel = "Create a project",
                    onAction = { showCreate = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
        }

        items(registry.projects, key = { it.id }) { project ->
            val isActive = project.id == registry.activeId
            val context = ProjectPolicy.contextOf(project, activeSlot)
            val linked = threads.filter { it.projectId == project.id }
            ProjectCard(
                project = project,
                context = context,
                isActive = isActive,
                threadCount = linked.size,
                accountLabel = accounts.accounts.firstOrNull { it.slot == project.accountSlot }?.shortName,
                conversationOwner = accounts.accounts
                    .firstOrNull { it.slot == project.conversationSlot }?.shortName,
                onSelect = { scope.launch { container.projects.select(project.id) } },
                onOpenConversation = {
                    val url = project.conversationUrl ?: return@ProjectCard
                    if (context != ProjectContext.CONTINUES) {
                        feedback = "That conversation belongs to another account, so opening it here " +
                            "would show an empty page. Switch account slots first."
                        return@ProjectCard
                    }
                    container.session.loadUrlInSession(url)
                    navigator.navigate(ArenaDestination.CHAT.route) { launchSingleTop = true }
                },
                onEdit = { editing = project },
                onAddFile = { fileFor = project },
                onAskDelete = { deleteFor = project },
                onTrackCurrentPage = {
                    val slot = activeSlot
                    if (slot == null) {
                        feedback = "Select an account slot first — a conversation lives in one."
                        return@ProjectCard
                    }
                    scope.launch {
                        val url = container.session.state.value.url.takeIf { it.isNotBlank() }
                        if (url == null) {
                            feedback = "Nothing is open in the Chat tab yet."
                        } else {
                            container.projects.linkConversation(project.id, url, slot)
                            feedback = "Linked this page to ${project.shortName}."
                        }
                    }
                },
            )
        }

        feedback?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = ArenaPalette.Emerald,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showCreate) {
        PromptDialog(
            title = "New project",
            explanation = "The name is yours, not Arena's. It is stored on this device and it is what " +
                "survives every account switch, logout and expired session.",
            placeholder = "Android game development",
            confirmLabel = "Create",
            limit = ProjectPolicy.MAX_NAME_LENGTH,
            onDismiss = { showCreate = false },
            onSubmit = { raw ->
                scope.launch {
                    when (val outcome = container.projects.create(raw)) {
                        is ProjectPolicy.AddOutcome.Created -> {
                            showCreate = false
                            feedback = null
                        }

                        is ProjectPolicy.AddOutcome.Full ->
                            feedback = "This app keeps ${ProjectPolicy.MAX_PROJECTS} projects locally. " +
                                "Delete one before adding another."

                        is ProjectPolicy.AddOutcome.InvalidName -> feedback = outcome.reason
                        is ProjectPolicy.AddOutcome.Duplicate -> {
                            // Same name = same work: open the project that exists rather than creating
                            // a second one the user will have to tell apart later.
                            container.projects.select(outcome.id)
                            showCreate = false
                            feedback = "\"${outcome.name}\" already exists — it is now the active project."
                        }
                    }
                }
            },
        )
    }

    editing?.let { project ->
        ProjectEditorDialog(
            project = project,
            onDismiss = { editing = null },
            onRename = { raw ->
                scope.launch {
                    val result = container.projects.rename(project.id, raw)
                    if (result is ProjectPolicy.RenameOutcome.Rejected) {
                        feedback = result.reason
                    } else {
                        editing = null
                    }
                }
            },
            onInstructions = { text ->
                scope.launch { container.projects.setInstructions(project.id, text) }
            },
            onUnlink = {
                scope.launch {
                    container.projects.unlinkConversation(project.id)
                    editing = null
                    feedback = "Conversation unlinked. Local notes and files are untouched."
                }
            },
        )
    }

    fileFor?.let { project ->
        PromptDialog(
            title = "Add a file to ${project.shortName}",
            explanation = "This app cannot read a repository or upload anything, so a file entry is an " +
                "index you keep — the name of the thing and what it is for.",
            placeholder = "core/game.cpp",
            confirmLabel = "Add",
            limit = ProjectPolicy.MAX_FILE_NAME,
            onDismiss = { fileFor = null },
            onSubmit = { raw ->
                scope.launch {
                    when (val outcome = container.projects.addFile(project.id, raw, "")) {
                        is ProjectPolicy.AddFileOutcome.Created -> fileFor = null
                        is ProjectPolicy.AddFileOutcome.Full ->
                            feedback = "${project.shortName} already lists ${ProjectPolicy.MAX_FILES} files."

                        is ProjectPolicy.AddFileOutcome.Invalid -> feedback = outcome.reason
                        ProjectPolicy.AddFileOutcome.Duplicate ->
                            feedback = "That file is already listed."
                    }
                }
            },
        )
    }

    deleteFor?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${project.shortName}?") },
            text = {
                Text(
                    "The project and its instructions go away. The threads it was filed under stay in " +
                        "History, unfiled, and Arena keeps its own transcripts untouched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = project.id
                        scope.launch {
                            container.projects.remove(id)
                            deleteFor = null
                            feedback = "${project.name} deleted. Nothing was sent to Arena."
                        }
                    },
                ) { Text("Delete project", color = ArenaPalette.Rose) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    context: ProjectContext,
    isActive: Boolean,
    threadCount: Int,
    accountLabel: String?,
    conversationOwner: String?,
    onSelect: () -> Unit,
    onOpenConversation: () -> Unit,
    onEdit: () -> Unit,
    onAddFile: () -> Unit,
    onAskDelete: () -> Unit,
    onTrackCurrentPage: () -> Unit,
) {
    val tint = when (context) {
        ProjectContext.CONTINUES -> ArenaPalette.Emerald
        ProjectContext.OTHER_ACCOUNT -> ArenaPalette.Amber
        ProjectContext.LOCAL_ONLY -> ArenaPalette.TextMuted
    }
    ArenaCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, if (isActive) tint.copy(alpha = 0.55f) else ArenaPalette.OutlineSoft),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectGlyph(icon = Icons.Filled.Description, tint = tint)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = context.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Active project",
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (project.instructions.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = project.instructions.take(280) + if (project.instructions.length > 280) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip(label = "${threadCount} thread(s)", leadingIcon = Icons.Filled.History)
            InfoChip(
                label = if (project.files.isEmpty()) "no files" else "${project.files.size} file(s)",
                leadingIcon = Icons.Filled.InsertDriveFile,
            )
            accountLabel?.let { InfoChip(label = it, leadingIcon = Icons.Filled.Person) }
        }

        if (context == ProjectContext.OTHER_ACCOUNT && conversationOwner != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = ProjectContext.OTHER_ACCOUNT.explanation(conversationOwner),
                style = MaterialTheme.typography.bodySmall,
                color = ArenaPalette.Amber,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isActive) {
                PrimaryButton(text = "Open in Chat", onClick = onOpenConversation, leadingIcon = Icons.Filled.OpenInNew)
            } else {
                GhostButton(text = "Make active", onClick = onSelect)
            }
            GhostButton(text = "Link page", onClick = onTrackCurrentPage)
            Spacer(Modifier.weight(1f))
            GhostButton(text = "Edit", onClick = onEdit)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            GhostButton(text = "Add file", onClick = onAddFile)
            GhostButton(text = "Delete", onClick = onAskDelete, destructive = true)
        }
    }
}

private fun ProjectContext.label(): String = when (this) {
    ProjectContext.CONTINUES -> "continues in Arena"
    ProjectContext.OTHER_ACCOUNT -> "another account holds it"
    ProjectContext.LOCAL_ONLY -> "local workspace only"
}

@Composable
private fun ProjectGlyph(icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.14f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ProjectEditorDialog(
    project: Project,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onInstructions: (String) -> Unit,
    onUnlink: () -> Unit,
) {
    var name by remember { mutableStateOf(project.name) }
    var instructions by remember { mutableStateOf(project.instructions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Standing instructions for this project") },
                    supportingText = {
                        Text(
                            "${instructions.length}/${ProjectPolicy.MAX_INSTRUCTIONS} · kept on this device",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    minLines = 4,
                    maxLines = 8,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                project.conversationUrl?.let { url ->
                    Text(
                        text = "Linked conversation: ${url.takeLast(46)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onUnlink) { Text("Unlink", color = ArenaPalette.Rose) }
                }
                project.files.takeIf { it.isNotEmpty() }?.let { files ->
                    Spacer(Modifier.height(6.dp))
                    files.forEach { file ->
                        Text(
                            text = "· ${file.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onInstructions(instructions) }) { Text("Save instructions") }
                TextButton(onClick = { onRename(name) }) { Text("Rename") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PromptDialog(
    title: String,
    explanation: String,
    placeholder: String,
    confirmLabel: String,
    limit: Int,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= limit) text = it },
                    label = { Text("Name") },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
