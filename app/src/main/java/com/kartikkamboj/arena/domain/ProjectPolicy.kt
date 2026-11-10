package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.Project
import com.kartikkamboj.arena.data.model.ProjectContext
import com.kartikkamboj.arena.data.model.ProjectFile
import java.util.UUID

/**
 * The rules of the project workspace, as pure functions.
 *
 * Everything the product promises about continuity is decided here rather than in a screen, so it
 * can be asserted without a device: a project never changes identity, no project is ever created
 * implicitly, account switching may only *annotate* a project, and the app never claims a remote
 * conversation is portable when the service gives it no such API.
 */
object ProjectPolicy {

    const val MAX_PROJECTS = 24
    const val MIN_NAME_LENGTH = 2
    const val MAX_NAME_LENGTH = 48
    const val MAX_INSTRUCTIONS = 4_000
    const val MAX_FILES = 40
    const val MAX_FILE_NAME = 80
    const val MAX_FILE_NOTE = 240

    sealed interface AddOutcome {
        data class Created(val project: Project) : AddOutcome
        data class Full(val limit: Int) : AddOutcome
        data class InvalidName(val reason: String) : AddOutcome
        data class Duplicate(val id: String, val name: String) : AddOutcome
    }

    sealed interface RenameOutcome {
        data object Applied : RenameOutcome
        data class Rejected(val reason: String) : RenameOutcome
    }

    sealed interface AddFileOutcome {
        data class Created(val file: ProjectFile) : AddFileOutcome
        data class Full(val limit: Int) : AddFileOutcome
        data class Invalid(val reason: String) : AddFileOutcome
        data object Duplicate : AddFileOutcome
    }

    private const val ARENA_ORIGIN = "https://arena.ai/"
    private const val ARENA_WWW_ORIGIN = "https://www.arena.ai/"

    /** Arena pages that list things instead of being a conversation. */
    private val INDEX_PAGES = setOf("history", "leaderboard", "blog", "api", "terms", "privacy")

    fun validateName(raw: String): String? {
        val name = raw.trim()
        return when {
            name.length < MIN_NAME_LENGTH -> "Give the project at least $MIN_NAME_LENGTH characters."
            name.length > MAX_NAME_LENGTH -> "Keep the name under $MAX_NAME_LENGTH characters."
            name.any { it == '<' || it == '>' || it == '\n' || it == '\t' } ->
                "No angle brackets or line breaks."

            else -> null
        }
    }

    fun normaliseName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun create(rawName: String, existing: List<Project>, now: Long): AddOutcome {
        if (existing.size >= MAX_PROJECTS) return AddOutcome.Full(MAX_PROJECTS)
        val invalid = validateName(rawName)
        if (invalid != null) return AddOutcome.InvalidName(invalid)
        val name = normaliseName(rawName)
        existing.firstOrNull { normaliseName(it.name).equals(name, ignoreCase = true) }?.let {
            // "Never create a new project automatically" cuts both ways: a second project with the
            // same name is almost always the same work, so the existing one is returned instead.
            return AddOutcome.Duplicate(it.id, it.name)
        }
        return AddOutcome.Created(
            Project(
                id = UUID.randomUUID().toString(),
                name = name,
                createdAtMillis = now,
                lastOpenedAtMillis = now,
            ),
        )
    }

    fun rename(rawName: String): RenameOutcome {
        val invalid = validateName(rawName)
        return if (invalid == null) RenameOutcome.Applied else RenameOutcome.Rejected(invalid)
    }

    fun addFile(files: List<ProjectFile>, rawName: String, rawNote: String, now: Long): AddFileOutcome {
        if (files.size >= MAX_FILES) return AddFileOutcome.Full(MAX_FILES)
        val name = normaliseName(rawName)
        if (name.isBlank()) return AddFileOutcome.Invalid("A file needs a name.")
        if (name.length > MAX_FILE_NAME) {
            return AddFileOutcome.Invalid("Keep the file name under $MAX_FILE_NAME characters.")
        }
        if (files.any { it.name.equals(name, ignoreCase = true) }) return AddFileOutcome.Duplicate
        return AddFileOutcome.Created(
            ProjectFile(name = name, note = rawNote.trim().take(MAX_FILE_NOTE), addedAtMillis = now),
        )
    }

    fun clipInstructions(raw: String): String = raw.trim().take(MAX_INSTRUCTIONS)

    /**
     * The single rule the whole feature exists for. Switching accounts touches *nothing* about the
     * project itself: same id, same name, same instructions, same files, same linked conversation.
     * Only the "who was working on this" annotation moves, and it is informational.
     */
    fun annotateAccount(project: Project, slot: Int?, now: Long): Project =
        if (project.accountSlot == slot) project else project.copy(accountSlot = slot, lastOpenedAtMillis = now)

    /**
     * Whether the linked conversation can be reopened right now. `conversationSlot` is compared
     * against the live slot because Arena threads are per-account; claiming otherwise would send the
     * user to a page that renders "not found" and blame it on the app.
     */
    fun contextOf(project: Project, activeSlot: Int?): ProjectContext = when {
        project.conversationUrl.isNullOrBlank() -> ProjectContext.LOCAL_ONLY
        activeSlot == null -> ProjectContext.OTHER_ACCOUNT
        project.conversationSlot == activeSlot -> ProjectContext.CONTINUES
        else -> ProjectContext.OTHER_ACCOUNT
    }

    /**
     * The thread id as the site shows it, or null when the url is not an Arena conversation.
     *
     * The slug is recorded for one reason: it lets the UI print "thread abc12" as an identifier the
     * user can verify against their browser address bar. Anything that is not a thread — the history
     * index, a leaderboard page, a url with a query string — yields null rather than a plausible-
     * looking fragment of a path, because a wrong id is worse than no id.
     */
    fun conversationSlug(url: String?): String? {
        val clean = url?.trim()?.trimEnd('/') ?: return null
        val path = when {
            clean.startsWith(ARENA_ORIGIN) -> clean.removePrefix(ARENA_ORIGIN)
            clean.startsWith(ARENA_WWW_ORIGIN) -> clean.removePrefix(ARENA_WWW_ORIGIN)
            else -> return null
        }
        if (path.contains('?') || path.contains('#')) return null
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null
        if (segments.first() in INDEX_PAGES) return null
        val slug = segments.last()
        return slug.takeIf {
            it.length >= 3 && it.all { c -> c.isLetterOrDigit() || c in "-_." }
        }
    }

    /**
     * Which project is active after a deletion. As with account slots, the app does not pick a
     * replacement behind the user's back: an untouched selection survives, an unambiguous remainder
     * (exactly one project left) is adopted, and anything else is left for the user to choose.
     */
    fun selectionAfterRemoval(projects: List<Project>, activeId: String?, removedId: String): String? {
        val remaining = projects.filter { it.id != removedId }
        return when {
            remaining.isEmpty() -> null
            activeId != null && activeId != removedId && remaining.any { it.id == activeId } -> activeId
            remaining.size == 1 -> remaining.single().id
            else -> null
        }
    }

    fun sorted(projects: List<Project>): List<Project> =
        projects.sortedWith(compareByDescending<Project> { it.lastOpenedAtMillis }.thenBy { it.name.lowercase() })
}
