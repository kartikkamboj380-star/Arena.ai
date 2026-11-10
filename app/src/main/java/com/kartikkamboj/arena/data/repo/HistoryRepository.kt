package com.kartikkamboj.arena.data.repo

import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.data.model.ArenaMode
import com.kartikkamboj.arena.data.model.ModelSelection
import com.kartikkamboj.arena.data.model.ThreadRecord
import com.kartikkamboj.arena.data.model.ThreadSource
import com.kartikkamboj.arena.data.store.JsonFileStore
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A link title + href lifted out of the user's own arena.ai history page, on request. */
data class ExtractedLink(val title: String, val url: String)

@Serializable
data class ThreadHistory(
    val version: Int = CURRENT_VERSION,
    val threads: List<ThreadRecord> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Local ledger of conversations this client opened or imported.
 *
 * It is *not* a mirror of Arena's history: there is no API to read someone's transcript, so the
 * app stores pointers (title, slot, mode, model, timestamp, optional URL) plus whatever the user
 * writes in the note. [importFromArena] only records the titles the user can already see on their
 * own history page, and only when they press the button.
 */
class HistoryRepository(
    private val store: JsonFileStore,
    private val clock: Clock,
    private val maxRecords: Int = MAX_RECORDS,
) {

    private val mutex = Mutex()

    private val _threads = MutableStateFlow<List<ThreadRecord>>(emptyList())
    val threads: StateFlow<List<ThreadRecord>> = _threads.asStateFlow()

    fun load() {
        _threads.value = store.read(NAME, ThreadHistory.serializer())?.threads.orEmpty()
    }

    suspend fun trackOpen(
        slot: Int,
        mode: ArenaMode,
        url: String?,
        model: ModelSelection?,
        titleHint: String?,
    ): ThreadRecord {
        val now = clock.nowMillis()
        val key = url?.trimEnd('/') ?: "mode:${mode.name}"
        return mutex.withLock {
            val existing = _threads.value.firstOrNull { it.url?.trimEnd('/') == key && it.slot == slot }
            val record = if (existing != null) {
                existing.copy(
                    lastOpenedAtMillis = now,
                    mode = mode,
                    model = model ?: existing.model,
                    title = titleHint?.takeIf { it.isNotBlank() } ?: existing.title,
                )
            } else {
                ThreadRecord(
                    id = UUID.randomUUID().toString(),
                    slot = slot,
                    title = titleHint?.takeIf { it.isNotBlank() } ?: untitled(mode),
                    mode = mode,
                    openedAtMillis = now,
                    lastOpenedAtMillis = now,
                    url = url,
                    model = model,
                )
            }
            val updated = (listOf(record) + _threads.value.filterNot { it.id == record.id })
                .sortedWith(compareByDescending<ThreadRecord> { it.pinned }
                    .thenByDescending { it.lastOpenedAtMillis })
                .trimTo(maxRecords)
            _threads.value = updated
            persistLocked()
            record
        }
    }

    private fun untitled(mode: ArenaMode): String = "${mode.label} session"

    suspend fun setNote(id: String, note: String) = mutate(id) { it.copy(note = note.take(MAX_NOTE)) }

    suspend fun setTitle(id: String, title: String) = mutate(id) {
        it.copy(title = title.trim().take(120).ifBlank { it.title })
    }

    suspend fun togglePin(id: String) = mutate(id) { it.copy(pinned = !it.pinned) }

    suspend fun remove(id: String) {
        mutex.withLock {
            _threads.value = _threads.value.filterNot { it.id == id }
            persistLocked()
        }
    }

    suspend fun clearAll(): Int {
        return mutex.withLock {
            val count = _threads.value.size
            _threads.value = emptyList()
            persistLocked()
            AppLog.d("cleared $count local thread record(s)")
            count
        }
    }

    /**
     * Records titles scraped from the user's own history page. Existing records with the same URL
     * are refreshed rather than duplicated, and nothing is stored that was not visible on screen.
     */
    suspend fun importFromArena(slot: Int, links: List<ExtractedLink>): ImportSummary {
        val now = clock.nowMillis()
        val usable = links.filter { it.title.isNotBlank() }.take(MAX_IMPORT)
        return mutex.withLock {
            val current = _threads.value
            val byUrl = current.associateBy { it.url }
            var added = 0
            var refreshed = 0
            val next = current.toMutableList()
            usable.forEach { link ->
                val url = link.url.trimEnd('/')
                val existing = byUrl[url]?.takeIf { it.slot == slot }
                if (existing != null) {
                    val index = next.indexOfFirst { it.id == existing.id }
                    if (index >= 0) {
                        next[index] = existing.copy(title = link.title, lastOpenedAtMillis = now)
                        refreshed++
                    }
                } else {
                    next += ThreadRecord(
                        id = UUID.randomUUID().toString(),
                        slot = slot,
                        title = link.title.take(120),
                        openedAtMillis = now,
                        lastOpenedAtMillis = now,
                        url = url,
                        source = ThreadSource.IMPORTED_FROM_ARENA,
                    )
                    added++
                }
            }
            _threads.value = next.sortedWith(
                compareByDescending<ThreadRecord> { it.pinned }.thenByDescending { it.lastOpenedAtMillis },
            ).trimTo(maxRecords)
            persistLocked()
            ImportSummary(added, refreshed, _threads.value.size)
        }
    }

    data class ImportSummary(val added: Int, val refreshed: Int, val total: Int)

    suspend fun threadsFor(slot: Int): List<ThreadRecord> =
        _threads.value.filter { it.slot == slot }

    private suspend fun mutate(id: String, transform: (ThreadRecord) -> ThreadRecord) {
        mutex.withLock {
            _threads.value = _threads.value.map { if (it.id == id) transform(it) else it }
            persistLocked()
        }
    }

    private fun List<ThreadRecord>.trimTo(limit: Int): List<ThreadRecord> {
        if (size <= limit) return this
        val pinned = filter { it.pinned }
        val rest = filterNot { it.pinned }.sortedByDescending { it.lastOpenedAtMillis }
        return (pinned + rest).take(limit)
    }

    private fun persistLocked() {
        store.write(NAME, ThreadHistory.serializer(), ThreadHistory(threads = _threads.value))
    }

    private companion object {
        const val NAME = "history"
        const val MAX_RECORDS = 200
        const val MAX_NOTE = 2_000
        const val MAX_IMPORT = 50
    }
}
