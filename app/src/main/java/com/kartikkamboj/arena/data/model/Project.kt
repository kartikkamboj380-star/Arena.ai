package com.kartikkamboj.arena.data.model

import kotlinx.serialization.Serializable

/**
 * A project is the unit of work the user thinks in — "Android game", "Q3 report" — and it is
 * deliberately *not* tied to an account, a conversation or a browser tab.
 *
 * The reason this type exists at all: switching account slots must never mean starting over. Arena
 * has no API for renaming, importing or moving a conversation, so the app cannot own the transcript;
 * what it *can* own is the continuity — the id, the name, the instructions, the file list, the model
 * choice and the pointer back to the remote conversation. Those live here, and they survive every
 * account change, logout and expired session.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val instructions: String = "",
    /** The arena.ai page this project continues in, when the app has seen one. */
    val conversationUrl: String? = null,
    /** The id the *site* shows in that page's address — recorded, never invented. */
    val conversationSlug: String? = null,
    /** Which slot the conversation url was opened with. Arena threads are per-account. */
    val conversationSlot: Int? = null,
    /** Last account that worked on this project. A convenience, not an ownership relation. */
    val accountSlot: Int? = null,
    val modelId: String? = null,
    val files: List<ProjectFile> = emptyList(),
    val createdAtMillis: Long = 0L,
    val lastOpenedAtMillis: Long = 0L,
) {
    val shortName: String get() = name.take(18)

    /** Locally recorded threads that belong to this project, newest first (joined in the repo). */
    val hasLocalContext: Boolean get() = instructions.isNotBlank() || files.isNotEmpty()
}

/**
 * A file the user says belongs to the project. This client cannot read a repository, so a file is a
 * name plus an optional note — an index the user keeps, not a synced copy. Saying otherwise in the
 * UI would be a lie about a capability the app does not have.
 */
@Serializable
data class ProjectFile(
    val name: String,
    val note: String = "",
    val addedAtMillis: Long = 0L,
)

@Serializable
data class ProjectRegistry(
    val version: Int = CURRENT_VERSION,
    val activeId: String? = null,
    val projects: List<Project> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Whether the *service* can carry on where the user left off, as opposed to whether this app can.
 *
 * The distinction is the honest part of this design: locally the project is always intact, but the
 * conversation itself lives in Arena under one account, and no supported mechanism moves it.
 */
enum class ProjectContext {
    /** The recorded conversation belongs to the account currently selected: it can be reopened. */
    CONTINUES,

    /** A conversation is recorded, but under a different account — switching to it is the only way. */
    OTHER_ACCOUNT,

    /** Nothing remote was ever recorded; the project exists only as local notes and files. */
    LOCAL_ONLY,
    ;

    fun explanation(accountLabel: String?): String = when (this) {
        CONTINUES -> "Arena's own conversation for this project is reachable from this account."
        OTHER_ACCOUNT -> "This conversation belongs to $accountLabel, not to the account on screen. " +
            "Arena keeps threads per account; the app cannot copy one across."
        LOCAL_ONLY -> "No Arena conversation is linked yet. Instructions, files and history stay " +
            "here on this device and are carried into whichever account you use."
    }
}
