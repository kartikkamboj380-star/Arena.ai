package com.kartikkamboj.arena.data.model

import kotlinx.serialization.Serializable

/**
 * Arena's publicly documented conversation modes, taken from the composer on https://arena.ai/.
 *
 * These are *entry points* in the web session, not model ids. The app can open them, but the
 * model that answers a Battle is chosen by Arena (blind evaluation), which is exactly why
 * [ModelSelection] is kept separate from the mode.
 */
@Serializable
enum class ArenaMode(
    val label: String,
    val blurb: String,
    val entryUrl: String,
    val revealsModelIdentity: Boolean,
) {
    AUTO(
        label = "Auto",
        blurb = "Arena picks the pair. Fastest way in, no choices to make.",
        entryUrl = "https://arena.ai/",
        revealsModelIdentity = false,
    ),
    BATTLE(
        label = "Battle",
        blurb = "Two anonymous models answer the same prompt; you vote, then the names are revealed.",
        entryUrl = "https://arena.ai/",
        revealsModelIdentity = false,
    ),
    SIDE_BY_SIDE(
        label = "Side by Side",
        blurb = "Pick two models yourself and compare the answers next to each other.",
        entryUrl = "https://arena.ai/",
        revealsModelIdentity = true,
    ),
    DIRECT(
        label = "Direct",
        blurb = "One model at a time — the closest thing to a plain chat assistant.",
        entryUrl = "https://arena.ai/",
        revealsModelIdentity = true,
    ),
    AGENT(
        label = "Agent",
        blurb = "Agentic runs with tools, browsing and multi-step work.",
        entryUrl = "https://arena.ai/agent",
        revealsModelIdentity = true,
    ),
    WEBDEV(
        label = "WebDev",
        blurb = "Prompt-to-app builds: landing pages, dashboards, playable games.",
        entryUrl = "https://arena.ai/",
        revealsModelIdentity = false,
    ),
    ;
}

/**
 * A locally tracked conversation.
 *
 * The transcript itself lives in Arena's account history (https://arena.ai/history) — this client
 * has no API access to it. What the app owns is the *pointer*: which slot opened it, when, which
 * mode was in use, plus the user's own note. "Import from Arena" adds titles read off the user's
 * own history page when they ask for it, and nothing is invented.
 */
@Serializable
data class ThreadRecord(
    val id: String,
    val slot: Int,
    val title: String,
    val mode: ArenaMode = ArenaMode.AUTO,
    val openedAtMillis: Long = 0L,
    val lastOpenedAtMillis: Long = 0L,
    val url: String? = null,
    val note: String = "",
    val pinned: Boolean = false,
    val model: ModelSelection? = null,
    val source: ThreadSource = ThreadSource.LOCAL,
)

@Serializable
enum class ThreadSource { LOCAL, IMPORTED_FROM_ARENA }

/** The model the user chose to pin for a thread. Applied inside the web composer, never silently. */
@Serializable
data class ModelSelection(
    val id: String,
    val displayName: String,
    val vendor: String? = null,
    val source: ModelSource,
    val availability: ModelAvailability,
)

@Serializable
enum class ModelSource {
    /** Read from the live public leaderboard page inside the web session. */
    LEADERBOARD_LIVE,

    /** Pinned by the user after they applied it in Arena's own picker. */
    USER_PINNED,

    /** Arena's own selector entry ("Auto", "Battle pair"). */
    ARENA_SELECTOR,
}

@Serializable
enum class ModelAvailability {
    /** Arena decides; nothing to choose. */
    AUTOMATIC,

    /** The name is known to exist on the service, but this client cannot select it directly. */
    SELECTABLE_IN_WEB_UI,

    /** Seen in a leaderboard snapshot; may not currently be offered for chat. */
    INFORMATIONAL_ONLY,

    /** Stale: seen before, absent from the latest snapshot. */
    UNAVAILABLE,
}

@Serializable
data class ModelEntry(
    val id: String,
    val displayName: String,
    val vendor: String? = null,
    val source: ModelSource,
    val availability: ModelAvailability,
    val note: String? = null,
    val rank: Int? = null,
    val score: Double? = null,
    val addedAtMillis: Long = 0L,
    val pinned: Boolean = false,
) {
    fun toSelection(): ModelSelection = ModelSelection(
        id = id,
        displayName = displayName,
        vendor = vendor,
        source = source,
        availability = availability,
    )
}

/** Catalog state for the Models screen. */
@Serializable
data class ModelCatalog(
    val version: Int = CURRENT_VERSION,
    val pinnedIds: List<String> = emptyList(),
    val models: List<ModelEntry> = emptyList(),
    val lastRefreshAtMillis: Long = 0L,
    val activeSelectionId: String? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }

    fun activeSelection(): ModelSelection? =
        models.firstOrNull { it.id == activeSelectionId }?.toSelection()

    fun withRefresh(refreshed: List<ModelEntry>, now: Long): ModelCatalog {
        val pinned = models.filter { it.pinned }.associateBy { it.id }
        val merged = refreshed.map { entry ->
            pinned[entry.id]?.let { entry.copy(pinned = true) } ?: entry
        }
        val keepPinnedOnly = if (merged.isEmpty()) models.filter { it.pinned } else merged
        return copy(
            models = keepPinnedOnly,
            lastRefreshAtMillis = now,
            pinnedIds = keepPinnedOnly.filter { it.pinned }.map { it.id },
        )
    }
}
