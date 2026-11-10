package com.kartikkamboj.arena.data.repo

import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.data.model.ModelAvailability
import com.kartikkamboj.arena.data.model.ModelCatalog
import com.kartikkamboj.arena.data.model.ModelEntry
import com.kartikkamboj.arena.data.model.ModelSelection
import com.kartikkamboj.arena.data.model.ModelSource
import com.kartikkamboj.arena.data.store.JsonFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The user's model list: names confirmed in Arena's own picker, plus entries extracted from the
 * public leaderboard page during a web session.
 *
 * What this class will not do is pretend a model is *selectable* from the app. There is no public
 * Arena API for choosing a model, so a selection is recorded as a label that the UI shows in the
 * chat header and that the user applies inside Arena's composer. [ModelAvailability] carries that
 * distinction into every screen, and stale entries are demoted to
 * [ModelAvailability.UNAVAILABLE] instead of being deleted, so "I used to see this model" stays
 * visible without implying it still works.
 */
class ModelRepository(
    private val store: JsonFileStore,
    private val clock: Clock,
    private val maxEntries: Int = 120,
) {

    private val mutex = Mutex()

    private val _catalog = MutableStateFlow(ModelCatalog())
    val catalog: StateFlow<ModelCatalog> = _catalog.asStateFlow()

    fun load() {
        _catalog.value = store.read(NAME, ModelCatalog.serializer()) ?: ModelCatalog()
    }

    val models: List<ModelEntry> get() = _catalog.value.models

    fun activeSelection(): ModelSelection? = _catalog.value.activeSelection()

    fun find(id: String): ModelEntry? = models.firstOrNull { it.id == id }

    /**
     * Merge one leaderboard snapshot into the catalog.
     *
     * Live entries replace other live entries (they are a view of one page at one moment), while
     * user-pinned entries are always kept and re-marked [ModelAvailability.UNAVAILABLE] when they
     * are missing from the newest snapshot.
     */
    suspend fun applySnapshot(snapshot: List<ModelEntry>): SnapshotResult {
        val now = clock.nowMillis()
        return mutex.withLock {
            val current = _catalog.value
            val pinned = current.models.filter { it.pinned || it.source == ModelSource.USER_PINNED }
            val seen = snapshot.map { it.id }.toSet()
            val refreshed = snapshot.map { entry ->
                val known = pinned.firstOrNull { it.id == entry.id }
                entry.copy(
                    pinned = known?.pinned ?: false,
                    addedAtMillis = known?.addedAtMillis ?: now,
                )
            }
            val keptPinned = pinned.mapNotNull { entry ->
                if (entry.id in seen) null else entry.copy(availability = ModelAvailability.UNAVAILABLE)
            }
            val merged = (refreshed + keptPinned)
                .distinctBy { it.id }
                .sortedWith(compareBy<ModelEntry> { it.rank == null }.thenBy { it.rank ?: Int.MAX_VALUE })
                .take(maxEntries)
            val next = current.copy(models = merged, lastRefreshAtMillis = now)
            _catalog.value = next
            persistLocked()
            SnapshotResult(
                total = merged.size,
                live = refreshed.size,
                demoted = keptPinned.count { it.availability == ModelAvailability.UNAVAILABLE },
            )
        }
    }

    data class SnapshotResult(val total: Int, val live: Int, val demoted: Int)

    /** User declares "I picked this model in Arena". Recorded as pinned, never as a live toggle. */
    suspend fun confirm(displayName: String, vendor: String?, note: String?): ConfirmResult {
        val cleaned = displayName.trim()
        if (cleaned.length < 2) return ConfirmResult.Rejected("Use the model's full name, e.g. as Arena shows it.")
        if (cleaned.length > 60) return ConfirmResult.Rejected("Keep the name under 60 characters.")
        return mutex.withLock {
            val id = "user:" + cleaned.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val existing = _catalog.value.models.firstOrNull { it.id == id }
            val entry = ModelEntry(
                id = id,
                displayName = cleaned,
                vendor = vendor?.trim()?.takeIf { it.isNotEmpty() }?.take(40),
                source = ModelSource.USER_PINNED,
                availability = ModelAvailability.SELECTABLE_IN_WEB_UI,
                note = note?.trim()?.take(160),
                addedAtMillis = existing?.addedAtMillis ?: clock.nowMillis(),
                pinned = true,
                rank = existing?.rank,
                score = existing?.score,
            )
            val models = listOf(entry) + _catalog.value.models.filterNot { it.id == id }
            _catalog.value = _catalog.value.copy(models = models.take(maxEntries))
            persistLocked()
            AppLog.d("confirmed model entry ${entry.displayName}")
            ConfirmResult.Accepted(entry)
        }
    }

    sealed interface ConfirmResult {
        data class Accepted(val entry: ModelEntry) : ConfirmResult
        data class Rejected(val reason: String) : ConfirmResult
    }

    suspend fun setPinned(id: String, pinned: Boolean) = mutate(id) { it.copy(pinned = pinned) }

    suspend fun remove(id: String): Boolean {
        return mutex.withLock {
            val target = _catalog.value.models.firstOrNull { it.id == id } ?: return@withLock false
            _catalog.value = _catalog.value.copy(
                models = _catalog.value.models.filterNot { it.id == id },
                // Dropping the entry that was selected must also drop the selection, otherwise the
                // chat header would keep naming a model that is no longer in the catalog.
                activeSelectionId = _catalog.value.activeSelectionId?.takeIf { it != id },
                pinnedIds = _catalog.value.pinnedIds.filterNot { it == id },
            )
            persistLocked()
            AppLog.d("removed model entry $id")
            true
        }
    }

    /**
     * Records the model the user wants shown as selected. Always triggered by a tap; nothing here
     * rotates a model automatically, which is also what the rate-limit rules require.
     */
    suspend fun select(id: String?): SelectionResult {
        return mutex.withLock {
            if (id == null) {
                _catalog.value = _catalog.value.copy(activeSelectionId = null)
                persistLocked()
                return@withLock SelectionResult.Cleared
            }
            val entry = _catalog.value.models.firstOrNull { it.id == id }
                ?: return@withLock SelectionResult.Unknown(id)
            if (entry.availability == ModelAvailability.UNAVAILABLE) {
                _catalog.value = _catalog.value.copy(activeSelectionId = id)
                persistLocked()
                return@withLock SelectionResult.SelectedUnavailable(entry)
            }
            _catalog.value = _catalog.value.copy(activeSelectionId = id)
            persistLocked()
            SelectionResult.Selected(entry.toSelection())
        }
    }

    sealed interface SelectionResult {
        data class Selected(val selection: ModelSelection) : SelectionResult
        data class SelectedUnavailable(val entry: ModelEntry) : SelectionResult
        data object Cleared : SelectionResult
        data class Unknown(val id: String) : SelectionResult
    }

    suspend fun clearCache() {
        mutex.withLock {
            val pinned = _catalog.value.models.filter { it.pinned || it.source == ModelSource.USER_PINNED }
            _catalog.value = _catalog.value.copy(models = pinned, lastRefreshAtMillis = 0L)
            persistLocked()
        }
    }

    /** Count by availability, for the Models screen header. */
    fun availabilityCounts(): Map<ModelAvailability, Int> =
        models.groupingBy { it.availability }.eachCount()

    private suspend fun mutate(id: String, transform: (ModelEntry) -> ModelEntry) {
        mutex.withLock {
            _catalog.value = _catalog.value.copy(
                models = _catalog.value.models.map { if (it.id == id) transform(it) else it },
            )
            persistLocked()
        }
    }

    private fun persistLocked() {
        store.write(NAME, ModelCatalog.serializer(), _catalog.value)
    }

    private companion object {
        const val NAME = "models"
    }
}
