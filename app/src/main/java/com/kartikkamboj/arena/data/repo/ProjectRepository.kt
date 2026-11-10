package com.kartikkamboj.arena.data.repo

import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.data.model.Project
import com.kartikkamboj.arena.data.model.ProjectContext
import com.kartikkamboj.arena.data.model.ProjectFile
import com.kartikkamboj.arena.data.model.ProjectRegistry
import com.kartikkamboj.arena.data.model.ThreadRecord
import com.kartikkamboj.arena.data.store.JsonFileStore
import com.kartikkamboj.arena.domain.ProjectPolicy
import com.kartikkamboj.arena.domain.ProjectPolicy.AddFileOutcome
import com.kartikkamboj.arena.domain.ProjectPolicy.AddOutcome
import com.kartikkamboj.arena.domain.ProjectPolicy.RenameOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owner of the project workspace.
 *
 * The repository holds no coroutine scope and launches nothing: every mutation is a caller-visible
 * suspend function returning an outcome, because the whole point of a project is that it must never
 * change by accident. An implicit creation, an auto-switch, a silent rename — each would break the
 * promise that "Project A is still Project A after I change accounts", so each of them is a value
 * the caller has to look at instead.
 */
class ProjectRepository(
    private val store: JsonFileStore,
    private val clock: Clock,
    private val history: HistoryRepository,
) {

    private val mutex = Mutex()

    private val _registry = MutableStateFlow(ProjectRegistry())
    val registry: StateFlow<ProjectRegistry> = _registry.asStateFlow()

    val projects: List<Project> get() = _registry.value.projects

    val activeId: String? get() = _registry.value.activeId

    fun active(): Project? = find(activeId)

    fun find(id: String?): Project? = id?.let { wanted -> projects.firstOrNull { it.id == wanted } }

    fun load() {
        val persisted = store.read(NAME, ProjectRegistry.serializer()) ?: ProjectRegistry()
        val active = persisted.activeId?.takeIf { id -> persisted.projects.any { it.id == id } }
        _registry.value = persisted.copy(
            version = ProjectRegistry.CURRENT_VERSION,
            activeId = active,
            projects = ProjectPolicy.sorted(persisted.projects),
        )
        AppLog.d("loaded ${persisted.projects.size} project(s), active=${active ?: "none"}")
    }

    /** The only way a project comes into existence: the user asked for it, by name. */
    suspend fun create(rawName: String): AddOutcome {
        val now = clock.nowMillis()
        return mutex.withLock {
            val outcome = ProjectPolicy.create(rawName, _registry.value.projects, now)
            if (outcome is AddOutcome.Created) {
                _registry.value = _registry.value.copy(
                    // Opening a brand-new project is the one case where switching the view is not a
                    // surprise: the user just asked for it and typed its name.
                    activeId = outcome.project.id,
                    projects = ProjectPolicy.sorted(_registry.value.projects + outcome.project),
                )
                persistLocked()
                AppLog.d("created project ${outcome.project.name}")
            }
            outcome
        }
    }

    /** Points the workspace at [id]. `null` is allowed: "no project" is a real state. */
    suspend fun select(id: String?): SelectOutcome {
        if (id == null) {
            mutex.withLock {
                _registry.value = _registry.value.copy(activeId = null)
                persistLocked()
            }
            return SelectOutcome.Cleared
        }
        val project = find(id) ?: return SelectOutcome.Unknown(id)
        mutex.withLock {
            _registry.value = _registry.value.copy(
                activeId = id,
                projects = _registry.value.projects.map {
                    if (it.id == id) it.copy(lastOpenedAtMillis = clock.nowMillis()) else it
                }.let(ProjectPolicy::sorted),
            )
            persistLocked()
        }
        return SelectOutcome.Selected(project)
    }

    sealed interface SelectOutcome {
        data class Selected(val project: Project) : SelectOutcome
        data object Cleared : SelectOutcome
        data class Unknown(val id: String) : SelectOutcome
    }

    suspend fun rename(id: String, rawName: String): RenameOutcome {
        val invalid = ProjectPolicy.validateName(rawName)
        if (invalid != null) return RenameOutcome.Rejected(invalid)
        update(id) { it.copy(name = ProjectPolicy.normaliseName(rawName)) }
        return RenameOutcome.Applied
    }

    suspend fun setInstructions(id: String, text: String) {
        update(id) { it.copy(instructions = ProjectPolicy.clipInstructions(text)) }
    }

    suspend fun addFile(id: String, rawName: String, rawNote: String): AddFileOutcome {
        val now = clock.nowMillis()
        return mutex.withLock {
            val project = _registry.value.projects.firstOrNull { it.id == id }
                ?: return@withLock AddFileOutcome.Invalid("No such project.")
            val outcome = ProjectPolicy.addFile(project.files, rawName, rawNote, now)
            if (outcome is AddFileOutcome.Created) {
                applyLocked(id) { it.copy(files = it.files + outcome.file) }
            }
            outcome
        }
    }

    suspend fun removeFile(id: String, fileName: String) {
        update(id) { it.copy(files = it.files.filterNot { file -> file.name == fileName }) }
    }

    /**
     * Records what the app just saw on screen: the Arena page this project is being continued in, and
     * which account was selected at the time. The slot is stored with the url because that is the only
     * honest way to say "this conversation is reachable from Account 2, and from nowhere else".
     */
    suspend fun linkConversation(id: String, url: String?, slot: Int?) {
        val clean = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        update(id) { project ->
            project.copy(
                conversationUrl = clean ?: project.conversationUrl,
                conversationSlug = ProjectPolicy.conversationSlug(clean) ?: project.conversationSlug,
                conversationSlot = slot ?: project.conversationSlot,
            )
        }
    }

    suspend fun unlinkConversation(id: String) {
        update(id) { it.copy(conversationUrl = null, conversationSlug = null, conversationSlot = null) }
    }

    suspend fun setModel(id: String, modelId: String?) {
        update(id) { it.copy(modelId = modelId) }
    }

    /**
     * Called after an account switch. This is the moment a naive design would create a fresh session
     * document; here it updates one annotation and nothing else, so the project — id, name,
     * instructions, files, linked conversation, history — is bit-for-bit unchanged.
     */
    suspend fun onAccountChanged(slot: Int?) {
        val active = activeId ?: return
        mutex.withLock {
            applyLocked(active) { project -> ProjectPolicy.annotateAccount(project, slot, clock.nowMillis()) }
        }
    }

    suspend fun remove(id: String): RemoveResult {
        val target = find(id) ?: return RemoveResult.Unknown(id)
        // The ledger rows outlive the project, so their filing is released first: a row pointing at a
        // deleted id would be a thread nobody can open again.
        history.unfileProject(id)
        return mutex.withLock {
            val remaining = _registry.value.projects.filterNot { it.id == id }
            val newActive = ProjectPolicy.selectionAfterRemoval(
                projects = _registry.value.projects,
                activeId = _registry.value.activeId,
                removedId = id,
            )
            _registry.value = _registry.value.copy(activeId = newActive, projects = ProjectPolicy.sorted(remaining))
            persistLocked()
            // Local history rows are kept — they are the user's notes about a conversation, and
            // deleting the project must not quietly destroy them. The link is released instead.
            RemoveResult.Removed(id, target.name, newActive, target.files.size)
        }
    }

    sealed interface RemoveResult {
        data class Removed(
            val id: String,
            val name: String,
            val activeId: String?,
            val filesDropped: Int,
        ) : RemoveResult

        data class Unknown(val id: String) : RemoveResult
    }

    fun contextFor(id: String?, activeSlot: Int?): ProjectContext {
        val project = find(id) ?: return ProjectContext.LOCAL_ONLY
        return ProjectPolicy.contextOf(project, activeSlot)
    }

    /** The project's own thread list, joined out of the local history ledger. */
    fun threadsOf(id: String?): List<ThreadRecord> =
        if (id == null) emptyList() else history.threads.value.filter { it.projectId == id }

    private suspend fun update(id: String, transform: (Project) -> Project) = mutex.withLock {
        applyLocked(id, transform)
    }

    private fun applyLocked(id: String, transform: (Project) -> Project) {
        val updated = _registry.value.projects.map { if (it.id == id) transform(it) else it }
        if (updated == _registry.value.projects) return
        _registry.value = _registry.value.copy(projects = ProjectPolicy.sorted(updated))
        persistLocked()
    }

    private fun persistLocked() {
        store.write(NAME, ProjectRegistry.serializer(), _registry.value)
    }

    private companion object {
        const val NAME = "projects"
    }
}
