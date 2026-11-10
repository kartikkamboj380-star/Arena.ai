package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.Project
import com.kartikkamboj.arena.data.model.ProjectContext
import com.kartikkamboj.arena.data.model.ProjectFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The continuity promises, as arithmetic.
 *
 * "Switching accounts never resets the project" is the claim the whole workspace makes; this class is
 * where it stops being a sentence in a README. Everything here is pure, so a failure means the rule
 * itself changed, not that a device misbehaved.
 */
class ProjectPolicyTest {

    private fun project(
        id: String = "p1",
        name: String = "Android game",
        url: String? = null,
        conversationSlot: Int? = null,
        accountSlot: Int? = null,
        openedAt: Long = 100L,
    ) = Project(
        id = id,
        name = name,
        conversationUrl = url,
        conversationSlot = conversationSlot,
        accountSlot = accountSlot,
        createdAtMillis = 10L,
        lastOpenedAtMillis = openedAt,
    )

    @Test
    fun `a new project is created only with a usable name`() {
        val outcome = ProjectPolicy.create("Android game", emptyList(), 1L)
        assertTrue(outcome is ProjectPolicy.AddOutcome.Created)
        val created = (outcome as ProjectPolicy.AddOutcome.Created).project
        assertEquals("Android game", created.name)
        assertEquals(1L, created.createdAtMillis)
        assertEquals(1L, created.lastOpenedAtMillis)
        assertNull("a fresh project is not signed into anything", created.accountSlot)
        assertNull("and it has no remote conversation until one is seen", created.conversationUrl)
    }

    @Test
    fun `names are validated rather than truncated`() {
        assertTrue(ProjectPolicy.create("  ", emptyList(), 0L) is ProjectPolicy.AddOutcome.InvalidName)
        assertTrue(ProjectPolicy.create("A", emptyList(), 0L) is ProjectPolicy.AddOutcome.InvalidName)
        assertTrue(
            "the user must see why, not a silently shortened name",
            ProjectPolicy.create("x".repeat(60), emptyList(), 0L) is ProjectPolicy.AddOutcome.InvalidName,
        )
        assertNotNull(ProjectPolicy.validateName("<script>"))
        assertNull(ProjectPolicy.validateName("Q3 report"))
    }

    @Test
    fun `the same name returns the existing project instead of a second one`() {
        val existing = listOf(project(name = "Android game"))
        val outcome = ProjectPolicy.create("  android game  ", existing, 9L)
        assertTrue(
            "a duplicate name means the same work, so it must not split the workspace",
            outcome is ProjectPolicy.AddOutcome.Duplicate,
        )
        assertEquals("p1", (outcome as ProjectPolicy.AddOutcome.Duplicate).id)
    }

    @Test
    fun `the ceiling is refused, never evicted`() {
        val full = (1..ProjectPolicy.MAX_PROJECTS).map { project(id = "p$it", name = "Project $it") }
        assertTrue(ProjectPolicy.create("One more", full, 0L) is ProjectPolicy.AddOutcome.Full)
        assertEquals(
            "the oldest project is never dropped to make room",
            ProjectPolicy.MAX_PROJECTS,
            full.size,
        )
    }

    @Test
    fun `an account switch annotates the project and changes nothing else`() {
        val before = project(
            url = "https://arena.ai/thread/abc",
            conversationSlot = 1,
            accountSlot = 1,
        ).let {
            it.copy(
                instructions = "keep the physics fixed",
                files = listOf(ProjectFile("core/game.cpp", "main loop", 5L)),
                modelId = "live:model-a",
            )
        }
        val after = ProjectPolicy.annotateAccount(before, 3, 500L)

        assertEquals("the id is the project", before.id, after.id)
        assertEquals(before.name, after.name)
        assertEquals(before.instructions, after.instructions)
        assertEquals(before.files, after.files)
        assertEquals(before.modelId, after.modelId)
        assertEquals(
            "the linked conversation is not re-written when the account changes",
            before.conversationUrl,
            after.conversationUrl,
        )
        assertEquals(before.conversationSlot, after.conversationSlot)
        assertEquals(3, after.accountSlot)
        assertEquals(500L, after.lastOpenedAtMillis)
        assertEquals(
            "annotating with the same slot is a no-op, so a redundant switch cannot churn the store",
            before,
            ProjectPolicy.annotateAccount(before, 1, 9_000L),
        )
    }

    @Test
    fun `context depends on which account is looking`() {
        val thread = project(url = "https://arena.ai/thread/abc", conversationSlot = 2)
        assertEquals(
            ProjectContext.CONTINUES,
            ProjectPolicy.contextOf(thread, activeSlot = 2),
        )
        assertEquals(
            "the same project is local-only from another account, and that must be said plainly",
            ProjectContext.OTHER_ACCOUNT,
            ProjectPolicy.contextOf(thread, activeSlot = 1),
        )
        assertEquals(
            ProjectContext.OTHER_ACCOUNT,
            ProjectPolicy.contextOf(thread, activeSlot = null),
        )
        assertEquals(ProjectContext.LOCAL_ONLY, ProjectPolicy.contextOf(project(), 2))
        assertEquals(
            ProjectContext.LOCAL_ONLY,
            ProjectPolicy.contextOf(project(url = "   "), 2),
        )
    }

    @Test
    fun `every context explains itself without promising a move`() {
        listOf(ProjectContext.CONTINUES, ProjectContext.OTHER_ACCOUNT, ProjectContext.LOCAL_ONLY)
            .forEach { context ->
                val text = context.explanation("Account 2")
                assertTrue("copy must be usable: $context", text.length > 20)
                assertFalse(
                    "nothing may promise that a conversation can be copied between accounts",
                    text.contains("will be imported") || text.contains("moved automatically"),
                )
            }
    }

    @Test
    fun `a thread slug is read from the url or not at all`() {
        assertEquals("abc", ProjectPolicy.conversationSlug("https://arena.ai/thread/abc"))
        assertEquals("abc", ProjectPolicy.conversationSlug("https://www.arena.ai/battle/abc/"))
        assertNull("a search page is not a conversation", ProjectPolicy.conversationSlug("https://arena.ai/history"))
        assertNull("an index page is not a conversation", ProjectPolicy.conversationSlug("https://arena.ai/history/search"))
        assertNull(ProjectPolicy.conversationSlug("https://evil.example/thread/abc"))
        assertNull(ProjectPolicy.conversationSlug(null))
        assertNull("an id-shaped tail of 1-2 characters is not trusted", ProjectPolicy.conversationSlug("https://arena.ai/thread/x"))
        assertNull("query strings are not ids", ProjectPolicy.conversationSlug("https://arena.ai/thread/?x=1"))
    }

    @Test
    fun `files are capped, trimmed and de-duplicated`() {
        val none = emptyList<ProjectFile>()
        assertTrue(ProjectPolicy.addFile(none, "  ", "", 0L) is ProjectPolicy.AddFileOutcome.Invalid)
        assertTrue(
            ProjectPolicy.addFile(none, "x".repeat(90), "", 0L) is ProjectPolicy.AddFileOutcome.Invalid,
        )
        val first = ProjectPolicy.addFile(none, "core/app.kt", "entry point", 7L)
        assertTrue(first is ProjectPolicy.AddFileOutcome.Created)
        val file = (first as ProjectPolicy.AddFileOutcome.Created).file
        assertEquals("core/app.kt", file.name)
        assertEquals("entry point", file.note)
        assertEquals(7L, file.addedAtMillis)
        assertTrue(ProjectPolicy.addFile(listOf(file), "CORE/APP.KT", "", 8L) is ProjectPolicy.AddFileOutcome.Duplicate)
        assertEquals(
            "note text is clipped, because it is a label and not a document",
            ProjectPolicy.MAX_FILE_NOTE,
            ProjectPolicy.addFile(none, "a.cpp", "y".repeat(900), 0L)
                .let { (it as ProjectPolicy.AddFileOutcome.Created).file.note }.length,
        )
        val many = (1..ProjectPolicy.MAX_FILES).map { ProjectFile("f$it", "", 0L) }
        assertTrue(ProjectPolicy.addFile(many, "one too many", "", 0L) is ProjectPolicy.AddFileOutcome.Full)
    }

    @Test
    fun `instructions are clipped to a documented size`() {
        assertEquals("keep it short", ProjectPolicy.clipInstructions("  keep it short  "))
        assertEquals(
            ProjectPolicy.MAX_INSTRUCTIONS,
            ProjectPolicy.clipInstructions("z".repeat(ProjectPolicy.MAX_INSTRUCTIONS * 2)).length,
        )
    }

    @Test
    fun `deleting the active project does not pick a replacement behind the user's back`() {
        val a = project(id = "a", name = "A", openedAt = 3L)
        val b = project(id = "b", name = "B", openedAt = 9L)
        val c = project(id = "c", name = "C", openedAt = 5L)
        val all = listOf(a, b, c)

        assertEquals(
            "an untouched selection survives",
            "a",
            ProjectPolicy.selectionAfterRemoval(all, activeId = "a", removedId = "b"),
        )
        assertNull(
            "with two candidates left the user chooses; the app does not guess",
            ProjectPolicy.selectionAfterRemoval(all, activeId = "b", removedId = "b"),
        )
        assertEquals(
            "one remaining project is unambiguous",
            "c",
            ProjectPolicy.selectionAfterRemoval(listOf(a, c), activeId = "a", removedId = "a"),
        )
        assertNull(
            ProjectPolicy.selectionAfterRemoval(listOf(a), activeId = "a", removedId = "a"),
        )
    }

    @Test
    fun `sorting puts recent work first and is stable on names`() {
        val sorted = ProjectPolicy.sorted(
            listOf(
                project(id = "1", name = "Zeta", openedAt = 1L),
                project(id = "2", name = "alpha", openedAt = 5L),
                project(id = "3", name = "Beta", openedAt = 5L),
            ),
        )
        assertEquals("alpha", sorted[0].name)
        assertEquals("Beta", sorted[1].name)
        assertEquals("Zeta", sorted[2].name)
    }
}
