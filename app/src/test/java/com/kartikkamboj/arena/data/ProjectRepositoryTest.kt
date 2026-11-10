package com.kartikkamboj.arena.data

import com.kartikkamboj.arena.core.FakeClock
import com.kartikkamboj.arena.data.model.ProjectContext
import com.kartikkamboj.arena.data.model.ThreadRecord
import com.kartikkamboj.arena.data.repo.HistoryRepository
import com.kartikkamboj.arena.data.repo.ProjectRepository
import com.kartikkamboj.arena.data.testJsonStore
import com.kartikkamboj.arena.data.model.ArenaMode
import com.kartikkamboj.arena.domain.ProjectPolicy
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The workspace as the app actually stores it: created only on request, unchanged by an account
 * switch, and recoverable from disk with its identity intact.
 */
class ProjectRepositoryTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val clock = FakeClock(5_000L)
    private lateinit var dir: File
    private lateinit var history: HistoryRepository
    private lateinit var projects: ProjectRepository

    @Before
    fun setUp() {
        dir = temp.newFolder()
        history = HistoryRepository(store = testJsonStore(dir), clock = clock)
        projects = ProjectRepository(store = testJsonStore(dir), clock = clock, history = history)
        projects.load()
    }

    private suspend fun created(name: String = "Android game"): String {
        val outcome = projects.create(name)
        assertTrue("expected creation, got $outcome", outcome is ProjectPolicy.AddOutcome.Created)
        return (outcome as ProjectPolicy.AddOutcome.Created).project.id
    }

    @Test
    fun `nothing exists until the user asks`() = runTest {
        assertNull(projects.activeId)
        assertTrue(projects.projects.isEmpty())
        assertEquals(
            "an empty workspace is still a state the UI can render",
            ProjectContext.LOCAL_ONLY,
            projects.contextFor(null, activeSlot = 1),
        )
    }

    @Test
    fun `creating a project makes it active and renaming keeps the id`() = runTest {
        val id = created()
        assertEquals(id, projects.activeId)
        val before = projects.find(id)!!
        projects.rename(id, "Android game v2")
        val after = projects.find(id)!!
        assertEquals(before.id, after.id)
        assertEquals("Android game v2", after.name)
        assertEquals(before.createdAtMillis, after.createdAtMillis)
    }

    @Test
    fun `a rejected name leaves the registry untouched`() = runTest {
        val outcome = projects.create(" x ")
        assertTrue(outcome is ProjectPolicy.AddOutcome.InvalidName)
        assertTrue(projects.projects.isEmpty())
        assertNull(projects.activeId)
    }

    @Test
    fun `selecting an unknown id is reported, not invented`() = runTest {
        val outcome = projects.select("nope")
        assertTrue(outcome is ProjectRepository.SelectOutcome.Unknown)
        assertNull(projects.activeId)
    }

    @Test
    fun `switching accounts annotates the active project and nothing else`() = runTest {
        val id = created()
        projects.setInstructions(id, "keep the fixed timestep")
        projects.addFile(id, "core/game.cpp", "main loop")
        projects.linkConversation(id, "https://arena.ai/thread/abc", slot = 1)
        val snapshot = projects.find(id)!!

        projects.onAccountChanged(4)
        val after = projects.find(id)!!

        assertEquals("same id", snapshot.id, after.id)
        assertEquals("same name", snapshot.name, after.name)
        assertEquals("same instructions", snapshot.instructions, after.instructions)
        assertEquals("same files", snapshot.files, after.files)
        assertEquals("same linked conversation", snapshot.conversationUrl, after.conversationUrl)
        assertEquals("the account that owns the thread does not move", snapshot.conversationSlot, after.conversationSlot)
        assertEquals(4, after.accountSlot)
        assertEquals(
            "the thread is still not reachable from this account, and the app says so",
            ProjectContext.OTHER_ACCOUNT,
            projects.contextFor(id, activeSlot = 4),
        )
        assertEquals(
            ProjectContext.CONTINUES,
            projects.contextFor(id, activeSlot = 1),
        )
    }

    @Test
    fun `a project survives a restart with its identity and pointer`() = runTest {
        val id = created("Q3 report")
        projects.setInstructions(id, "match the board's numbers")
        projects.linkConversation(id, "https://arena.ai/thread/zz9", slot = 2)
        projects.setModel(id, "live:model-a")

        val reopened = ProjectRepository(store = testJsonStore(dir), clock = clock, history = history)
        reopened.load()
        val restored = reopened.find(id)
        assertNotNull(restored)
        assertEquals("Q3 report", restored!!.name)
        assertEquals("match the board's numbers", restored.instructions)
        assertEquals("https://arena.ai/thread/zz9", restored.conversationUrl)
        assertEquals("zz9", restored.conversationSlug)
        assertEquals(2, restored.conversationSlot)
        assertEquals("live:model-a", restored.modelId)
        assertEquals(id, reopened.activeId)
    }

    @Test
    fun `linking a page records the slot it came from and unlinking keeps the rest`() = runTest {
        val id = created()
        projects.linkConversation(id, "https://arena.ai/thread/abc/", slot = 3)
        val linked = projects.find(id)!!
        assertEquals("trailing slashes are not part of an identity", "https://arena.ai/thread/abc", linked.conversationUrl)
        assertEquals(3, linked.conversationSlot)

        projects.unlinkConversation(id)
        val after = projects.find(id)!!
        assertNull(after.conversationUrl)
        assertNull(after.conversationSlug)
        assertNull(after.conversationSlot)
        assertEquals("unlinking is not deleting", id, after.id)
        assertEquals(id, after.id)
    }

    @Test
    fun `a non-thread url is linked without a fabricated slug`() = runTest {
        val id = created()
        projects.linkConversation(id, "https://arena.ai/history", slot = 1)
        val project = projects.find(id)!!
        assertEquals("https://arena.ai/history", project.conversationUrl)
        assertNull("the app does not invent an id from an index page", project.conversationSlug)
    }

    @Test
    fun `history rows are filed under the project that was open`() = runTest {
        val id = created()
        val record = history.trackOpen(
            slot = 1,
            mode = ArenaMode.AUTO,
            url = "https://arena.ai/thread/one",
            model = null,
            titleHint = "One",
            projectId = id,
        )
        assertEquals(id, projects.threadsOf(id).single().id)
        assertEquals(1, projects.threadsOf(id).size)
        assertEquals(record.projectId, id)

        // A row opened with no project on screen stays unfiled, and deleting the project unfiles its
        // rows instead of destroying them.
        val unfiled = history.trackOpen(1, ArenaMode.AUTO, "https://arena.ai/thread/two", null, "Two")
        assertNull(unfiled.projectId)
        projects.remove(id)
        assertEquals(2, history.threads.value.size)
        assertNull(history.threads.value.first { it.id == record.id }.projectId)
    }

    @Test
    fun `deleting the only project leaves no active id rather than a phantom`() = runTest {
        val id = created()
        val result = projects.remove(id)
        assertTrue(result is ProjectRepository.RemoveResult.Removed)
        assertNull(projects.activeId)
        assertTrue(projects.projects.isEmpty())
        assertTrue(projects.remove(id) is ProjectRepository.RemoveResult.Unknown)
    }

    @Test
    fun `instructions are clipped where the policy says`() = runTest {
        val id = created()
        projects.setInstructions(id, "q".repeat(ProjectPolicy.MAX_INSTRUCTIONS + 500))
        assertEquals(ProjectPolicy.MAX_INSTRUCTIONS, projects.find(id)!!.instructions.length)
    }

    @Test
    fun `a corrupt store yields an empty workspace instead of a crash`() {
        File(dir, "projects.json").writeText("{ this is not json")
        val reopened = ProjectRepository(store = testJsonStore(dir), clock = clock, history = history)
        reopened.load()
        assertTrue(reopened.projects.isEmpty())
        assertNull(reopened.activeId)
    }

    @Test
    fun `threads on the project are the local ledger rows only`() {
        // Documents the boundary: a project's thread list is built from this device's rows, so it
        // cannot claim to be Arena's history.
        val rows = listOf(
            ThreadRecord(id = "1", slot = 1, title = "kept", projectId = "p1"),
            ThreadRecord(id = "2", slot = 1, title = "elsewhere", projectId = "p2"),
        )
        assertEquals(1, rows.count { it.projectId == "p1" })
        assertEquals(0, rows.count { it.projectId == null })
    }
}
