package com.kartikkamboj.arena.data.repo

import com.kartikkamboj.arena.core.FakeClock
import com.kartikkamboj.arena.data.model.ArenaMode
import com.kartikkamboj.arena.data.model.ModelAvailability
import com.kartikkamboj.arena.data.model.ModelEntry
import com.kartikkamboj.arena.data.model.ModelSource
import com.kartikkamboj.arena.data.model.ThreadSource
import com.kartikkamboj.arena.data.store.JsonConfig
import com.kartikkamboj.arena.data.store.JsonFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun storeIn(folder: TemporaryFolder): JsonFileStore =
    JsonFileStore(folder.newFolder(), JsonConfig.Default)

class HistoryRepositoryTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val clock = FakeClock(10_000L)

    private fun repo(maxRecords: Int = 200): HistoryRepository =
        HistoryRepository(storeIn(folder), clock, maxRecords)

    @Test
    fun `opening the same url twice refreshes the record instead of duplicating it`() = runTest {
        val history = repo()
        history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/thread/abc", null, "First title")
        clock.advanceMillis(5_000L)
        history.trackOpen(1, ArenaMode.DIRECT, "https://arena.ai/thread/abc/", null, "Renamed")

        val threads = history.threads.value
        assertEquals(1, threads.size)
        val record = threads.single()
        assertEquals("Renamed", record.title)
        assertEquals(ArenaMode.DIRECT, record.mode)
        assertEquals(15_000L, record.lastOpenedAtMillis)
        assertEquals(10_000L, record.openedAtMillis)
    }

    @Test
    fun `a blank title hint keeps the title already on file`() = runTest {
        val history = repo()
        history.trackOpen(1, ArenaMode.BATTLE, "https://arena.ai/thread/x", null, "Real title")
        val record = history.trackOpen(1, ArenaMode.BATTLE, "https://arena.ai/thread/x", null, "   ")
        assertEquals("Real title", record.title)
    }

    @Test
    fun `a slot never borrows another slot's record`() = runTest {
        val history = repo()
        history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/thread/x", null, "Shared link")
        history.trackOpen(2, ArenaMode.AUTO, "https://arena.ai/thread/x", null, "Shared link")
        assertEquals(2, history.threads.value.size)
        assertEquals(1, history.threadsFor(1).size)
        assertEquals(1, history.threadsFor(2).size)
    }

    @Test
    fun `a session with no url is still tracked by mode so nothing is lost`() = runTest {
        val history = repo()
        val record = history.trackOpen(1, ArenaMode.AGENT, url = null, model = null, titleHint = null)
        assertEquals("${ArenaMode.AGENT.label} session", record.title)
        assertEquals(1, history.threads.value.size)
        history.trackOpen(1, ArenaMode.AGENT, url = null, model = null, titleHint = null)
        assertEquals("the same undetermined session collapses into one row", 1, history.threads.value.size)
    }

    @Test
    fun `pinned entries sort above newer ones`() = runTest {
        val history = repo()
        history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/a", null, "A")
        history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/b", null, "B")
        val older = history.threads.value.last()
        assertEquals("A", older.title)
        history.togglePin(older.id)

        assertEquals("A", history.threads.value.first().title)
        assertTrue(history.threads.value.first().pinned)

        history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/c", null, "C")
        assertEquals("a new visit must not displace a pinned row", "A", history.threads.value.first().title)
    }

    @Test
    fun `notes are stored locally and clipped`() = runTest {
        val history = repo()
        val record = history.trackOpen(1, ArenaMode.SIDE_BY_SIDE, "https://arena.ai/t/1", null, "T")
        history.setNote(record.id, "x".repeat(5_000))
        val note = history.threads.value.single().note
        assertTrue(note.length <= 2_000)
        history.setTitle(record.id, "   ")
        assertEquals("T", history.threads.value.single().title)
    }

    @Test
    fun `removal and clearing`() = runTest {
        val history = repo()
        history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/a", null, "A")
        history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/b", null, "B")
        val pinned = history.threads.value.last()
        history.togglePin(pinned.id)

        history.remove(pinned.id)
        assertNull(history.threads.value.find { it.id == pinned.id })

        assertEquals(1, history.clearAll())
        assertTrue(history.threads.value.isEmpty())
    }

    @Test
    fun `the ledger is capped, and pinned rows survive the cap`() = runTest {
        val history = repo(maxRecords = 5)
        val first = history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/keep", null, "Keep me")
        history.togglePin(first.id)
        repeat(10) { index ->
            history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/thread/$index", null, "Thread $index")
        }
        val threads = history.threads.value
        assertEquals(5, threads.size)
        assertEquals("Keep me", threads.first().title)
    }

    @Test
    fun `import records only titles that were visible on the page`() = runTest {
        val history = repo()
        val links = listOf(
            ExtractedLink("My first chat", "https://arena.ai/thread/1"),
            ExtractedLink("Second chat", "https://arena.ai/thread/2"),
            ExtractedLink("   ", "https://arena.ai/thread/blank"),
        )
        val summary = history.importFromArena(1, links)
        assertEquals(2, summary.added)
        assertEquals(0, summary.refreshed)
        assertEquals(2, summary.total)
        assertEquals(
            ThreadSource.IMPORTED_FROM_ARENA,
            history.threads.value.first { it.url!!.endsWith("/thread/1") }.source,
        )

        // Importing again refreshes rather than duplicates.
        val again = history.importFromArena(1, listOf(ExtractedLink("My first chat (renamed)", "https://arena.ai/thread/1")))
        assertEquals(0, again.added)
        assertEquals(1, again.refreshed)
        assertEquals("My first chat (renamed)", history.threads.value.first { it.url!!.endsWith("/thread/1") }.title)
    }

    @Test
    fun `imported rows are attributed to the slot that was on screen`() = runTest {
        val history = repo()
        val links = (1..3).map { ExtractedLink("Chat $it", "https://arena.ai/thread/$it") }
        history.importFromArena(1, links)
        history.importFromArena(2, links.take(1))
        assertEquals(3, history.threads.value.size)
        assertEquals(1, history.threadsFor(2).size)
    }

    @Test
    fun `persistence round trips through the json store`() = runTest {
        val store = storeIn(folder)
        val history = HistoryRepository(store, clock)
        val record = history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/thread/keep", null, "Keep")
        history.togglePin(record.id)
        history.setNote(record.id, "note text")

        val reopened = HistoryRepository(store, clock)
        reopened.load()
        val restored = reopened.threads.value.single()
        assertEquals("Keep", restored.title)
        assertTrue(restored.pinned)
        assertEquals("note text", restored.note)
    }

    @Test
    fun `a missing file yields an empty ledger rather than a crash`() {
        val history = repo()
        history.load()
        assertTrue(history.threads.value.isEmpty())
    }

    @Test
    fun `the model of the moment is remembered with the record`() = runTest {
        val history = repo()
        val selection = com.kartikkamboj.arena.data.model.ModelSelection(
            id = "live:claude-sonnet",
            displayName = "Claude Sonnet",
            vendor = "Anthropic",
            source = ModelSource.LEADERBOARD_LIVE,
            availability = ModelAvailability.SELECTABLE_IN_WEB_UI,
        )
        history.trackOpen(1, ArenaMode.DIRECT, "https://arena.ai/thread/1", selection, "Chat")
        val record = history.threads.value.single()
        assertEquals("Claude Sonnet", record.model?.displayName)
    }
}

class ModelRepositoryTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val clock = FakeClock(10_000L)

    private fun repo(maxEntries: Int = 120): ModelRepository =
        ModelRepository(storeIn(folder), clock, maxEntries)

    private fun entry(
        id: String,
        name: String = id,
        rank: Int? = null,
        availability: ModelAvailability = ModelAvailability.SELECTABLE_IN_WEB_UI,
    ) = ModelEntry(
        id = id,
        displayName = name,
        vendor = "Vendor",
        source = ModelSource.LEADERBOARD_LIVE,
        availability = availability,
        rank = rank,
        addedAtMillis = 10_000L,
    )

    @Test
    fun `nothing is known before a snapshot arrives`() {
        val models = repo()
        models.load()
        assertTrue(models.models.isEmpty())
        assertNull(models.activeSelection())
        assertTrue(models.availabilityCounts().isEmpty())
    }

    @Test
    fun `a census replaces the live rows and only keeps user-pinned ones`() = runTest {
        val models = repo()
        models.applySnapshot(listOf(entry("a", "Model A", rank = 1), entry("b", "Model B", rank = 2)))
        models.setPinned("a", true)
        clock.advanceMillis(1_000L)

        val result = models.applySnapshot(listOf(entry("b", "Model B", rank = 1), entry("c", "Model C", rank = 2)))
        assertEquals(3, result.total)
        assertEquals(2, result.live)
        assertEquals(1, result.demoted)

        // A row the user pinned is kept, but demoted: present-but-stale, never presented as live.
        val a = models.find("a")!!
        assertEquals(ModelAvailability.UNAVAILABLE, a.availability)
        assertEquals("a stale row keeps the name it was seen under", "Model A", a.displayName)
        assertEquals(ModelAvailability.SELECTABLE_IN_WEB_UI, models.find("b")!!.availability)
        assertEquals(setOf("a", "b", "c"), models.models.map { it.id }.toSet())
        assertTrue(
            "rows are ordered by the rank the page reported",
            models.models.indexOfFirst { it.id == "c" } > models.models.indexOfFirst { it.id == "b" },
        )
        assertEquals(11_000L, models.catalog.value.lastRefreshAtMillis)
    }

    @Test
    fun `an unconfirmed live row simply disappears when the site stops listing it`() = runTest {
        val models = repo()
        models.applySnapshot(listOf(entry("a"), entry("b")))
        val result = models.applySnapshot(listOf(entry("b")))
        assertEquals(listOf("b"), models.models.map { it.id })
        assertEquals("nothing was pinned, so nothing was demoted", 0, result.demoted)
    }

    @Test
    fun `pinned rows are re-marked rather than dropped when a census omits them`() = runTest {
        val models = repo()
        models.applySnapshot(listOf(entry("a", "Model A")))
        models.setPinned("a", true)

        val result = models.applySnapshot(listOf(entry("b", "Model B")))
        assertEquals(1, result.demoted)
        val pinned = models.find("a")!!
        assertTrue(pinned.pinned)
        assertEquals(ModelAvailability.UNAVAILABLE, pinned.availability)
        assertEquals(ModelAvailability.SELECTABLE_IN_WEB_UI, models.find("b")!!.availability)
    }

    @Test
    fun `a user confirmation creates a pinned row with a stable id`() = runTest {
        val models = repo()
        val outcome = models.confirm("  Claude Opus  ", "Anthropic", "good at long docs")
        assertTrue(outcome is ModelRepository.ConfirmResult.Accepted)
        val entry = (outcome as ModelRepository.ConfirmResult.Accepted).entry
        assertEquals("user:claude-opus", entry.id)
        assertEquals("Claude Opus", entry.displayName)
        assertTrue(entry.pinned)
        assertEquals(ModelSource.USER_PINNED, entry.source)
        assertEquals("good at long docs", entry.note)

        // Confirming the same name again refreshes rather than duplicates.
        val again = models.confirm("Claude Opus", "Anthropic", null)
        assertEquals(1, models.models.size)
        assertEquals(entry.addedAtMillis, (again as ModelRepository.ConfirmResult.Accepted).entry.addedAtMillis)
    }

    @Test
    fun `junk names are refused with a message the user can act on`() = runTest {
        val models = repo()
        val short = models.confirm("a", null, null)
        assertTrue(short is ModelRepository.ConfirmResult.Rejected)
        assertNotNull((short as ModelRepository.ConfirmResult.Rejected).reason)

        val long = models.confirm("x".repeat(80), null, null)
        assertTrue(long is ModelRepository.ConfirmResult.Rejected)
        assertTrue(models.models.isEmpty())
    }

    @Test
    fun `selection is recorded as a label, and unknown ids are refused`() = runTest {
        val models = repo()
        assertTrue(models.select("nope") is ModelRepository.SelectionResult.Unknown)

        models.applySnapshot(listOf(entry("a", "Model A")))
        val selected = models.select("a")
        assertTrue(selected is ModelRepository.SelectionResult.Selected)
        assertEquals("Model A", (selected as ModelRepository.SelectionResult.Selected).selection.displayName)
        assertEquals("a", models.catalog.value.activeSelectionId)
        assertEquals("Model A", models.activeSelection()?.displayName)

        val cleared = models.select(null)
        assertTrue(cleared is ModelRepository.SelectionResult.Cleared)
        assertNull(models.activeSelection())
    }

    @Test
    fun `choosing a stale model is recorded but flagged, never presented as live`() = runTest {
        val models = repo()
        models.applySnapshot(listOf(entry("a")))
        models.confirm("Kept by hand", null, null)
        models.applySnapshot(listOf(entry("b")))

        val outcome = models.select("user:kept-by-hand")
        assertTrue(outcome is ModelRepository.SelectionResult.SelectedUnavailable)
        assertEquals(
            "the selection is still recorded so the header can show it honestly",
            "user:kept-by-hand",
            models.catalog.value.activeSelectionId,
        )
    }

    @Test
    fun `removing a row clears the selection that pointed at it`() = runTest {
        val models = repo()
        models.applySnapshot(listOf(entry("a"), entry("b")))
        models.select("a")
        assertTrue(models.remove("a"))
        assertFalse(models.remove("a"))
        assertNull(models.catalog.value.activeSelectionId)
        assertEquals(listOf("b"), models.models.map { it.id })
    }

    @Test
    fun `rows are ranked by what the page said and clipped to the cap`() = runTest {
        val models = repo(maxEntries = 3)
        models.applySnapshot(
            (1..10).map { index -> entry("m$index", "Model $index", rank = 11 - index) },
        )
        assertEquals(3, models.models.size)
        assertEquals(listOf(1, 2, 3), models.models.map { it.rank })
    }

    @Test
    fun `availability counts are what the header shows, never invented quota`() = runTest {
        val models = repo()
        models.applySnapshot(List(5) { entry("m$it", rank = it) })
        models.setPinned("m3", true)
        models.setPinned("m4", true)
        models.applySnapshot(List(3) { entry("m$it", rank = it) })
        val counts = models.availabilityCounts()
        assertEquals(3, counts[ModelAvailability.SELECTABLE_IN_WEB_UI])
        assertEquals(2, counts[ModelAvailability.UNAVAILABLE])
    }

    @Test
    fun `clearing the cache keeps user intent and forgets the census`() = runTest {
        val models = repo()
        models.applySnapshot(listOf(entry("a")))
        models.confirm("Pinned", null, null)

        models.clearCache()
        assertEquals(listOf("user:pinned"), models.models.map { it.id })
        assertEquals(0L, models.catalog.value.lastRefreshAtMillis)
    }

    @Test
    fun `pinning a live row keeps it across censuses`() = runTest {
        val models = repo()
        models.applySnapshot(listOf(entry("a"), entry("b")))
        models.setPinned("a", true)
        models.applySnapshot(listOf(entry("b")))
        assertEquals(setOf("a", "b"), models.models.map { it.id }.toSet())
        assertTrue(models.find("a")!!.pinned)
        models.setPinned("a", false)
        models.applySnapshot(listOf(entry("b")))
        assertEquals(listOf("b"), models.models.map { it.id })
    }

    @Test
    fun `the catalog survives a restart`() = runTest {
        val store = storeIn(folder)
        val models = ModelRepository(store, clock)
        models.applySnapshot(listOf(entry("a", "Model A", rank = 4)))
        models.confirm("Hand Picked", "Vendor", null)
        models.select("a")

        val reopened = ModelRepository(store, clock)
        reopened.load()
        assertEquals(2, reopened.models.size)
        assertEquals("Model A", reopened.find("a")?.displayName)
        assertEquals(4, reopened.find("a")?.rank)
        assertEquals("a", reopened.catalog.value.activeSelectionId)
        assertEquals(ModelAvailability.SELECTABLE_IN_WEB_UI, reopened.find("a")?.availability)
    }

    @Test
    fun `a corrupt file is moved aside instead of being trusted`() = runTest {
        val directory = folder.newFolder()
        File(directory, "models.json").writeText("{ this is not json")
        val models = ModelRepository(JsonFileStore(directory, JsonConfig.Default), clock)
        models.load()
        assertTrue(models.models.isEmpty())
        assertFalse(File(directory, "models.json").exists())
        assertTrue(File(directory, "models.corrupt").exists())
    }
}
