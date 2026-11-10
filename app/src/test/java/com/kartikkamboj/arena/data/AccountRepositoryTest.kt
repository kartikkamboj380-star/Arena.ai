package com.kartikkamboj.arena.data.repo

import com.kartikkamboj.arena.core.FakeClock
import com.kartikkamboj.arena.data.FakeIsolation
import com.kartikkamboj.arena.data.FakeSettings
import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.AuthProbeResult
import com.kartikkamboj.arena.data.model.ProbeVerdict
import com.kartikkamboj.arena.data.testJsonStore
import com.kartikkamboj.arena.domain.AccountPolicy
import com.kartikkamboj.arena.domain.SessionStateMachine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The account manager, driven against a fake isolation layer whose only extra job is to be strict.
 *
 * This is where "up to five accounts, each with its own session, never merged" is pinned down as
 * assertions rather than as a sentence in a README.
 */
class AccountRepositoryTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val clock = FakeClock(1_000L)
    private val settings = FakeSettings()
    private val isolation = FakeIsolation(clock)
    private lateinit var dir: File
    private lateinit var repo: AccountRepository

    private fun newRepo(): AccountRepository = AccountRepository(
        store = testJsonStore(dir),
        isolation = isolation,
        settings = settings,
        clock = clock,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Before
    fun setUp() {
        dir = temp.newFolder()
        repo = newRepo()
    }

    @Test
    fun `creating slots allocates 1 to 5 and stops there`() = runTest {
        val slots = (1..5).map { index ->
            val outcome = repo.add("Account $index")
            assertTrue("slot $index should be created", outcome is AccountPolicy.AddOutcome.Created)
            (outcome as AccountPolicy.AddOutcome.Created).slot
        }
        assertEquals(listOf(1, 2, 3, 4, 5), slots)
        assertTrue(repo.isFull())
        // Refused, not evicted: an existing session is never dropped to make room.
        val sixth = repo.add("One too many")
        assertTrue(sixth is AccountPolicy.AddOutcome.Full)
        assertEquals(5, repo.accounts.size)
    }

    @Test
    fun `creating a slot never activates it`() = runTest {
        repo.add("Solo")
        assertNull("creating must not select", repo.selection.value)
    }

    @Test
    fun `selecting an unknown slot is an error, not a silent no-op`() = runTest {
        assertTrue(repo.select(4) is AccountRepository.SelectOutcome.UnknownSlot)
    }

    @Test
    fun `selecting the current slot reports already-selected instead of churning the jar`() = runTest {
        repo.add("Ada")
        repo.select(1)
        assertTrue(repo.select(1) is AccountRepository.SelectOutcome.AlreadySelected)
    }

    @Test
    fun `a cold start with nothing stored is Not Connected`() = runTest {
        repo.add("Ada")
        repo.load()
        assertEquals(AccountStatus.NOT_CONNECTED, repo.find(1)?.status)
    }

    @Test
    fun `a stored jar from a previous launch is not presented as a live login`() = runTest {
        // Simulate having logged in during an earlier run, with one cookie in the jar.
        repo.add("Ada")
        repo.select(1)
        isolation.live["sid"] = "abc"
        repo.applyProbe(
            1,
            AuthProbeResult(
                observedAtMillis = clock.nowMillis(),
                verdict = ProbeVerdict.AUTHENTICATED,
                url = "https://arena.ai/",
                accountLabel = "me@corp",
            ),
        )
        repo.refreshJar(1)
        assertEquals(AccountStatus.CONNECTED, repo.find(1)?.status)

        // New process: same files, no probe yet.
        val reopened = newRepo()
        reopened.load()
        val status = reopened.find(1)?.status
        assertTrue(
            "a restored jar may at most be 'stored, unverified', never Connected",
            status == AccountStatus.NOT_CONNECTED || status == AccountStatus.SESSION_EXPIRED,
        )
        assertEquals(1, reopened.find(1)?.cookieCount)
    }

    @Test
    fun `switching accounts captures the outgoing jar and installs only the incoming one`() = runTest {
        repo.add("One")
        repo.add("Two")

        repo.select(1)
        isolation.live["sid"] = "slot-one"

        repo.select(2) // must snapshot slot 1 first
        assertEquals(1, isolation.stored[1]?.size)
        assertTrue("the shared jar must be empty for a slot with nothing stored", isolation.live.isEmpty())

        repo.select(1) // and restore it on the way back
        assertEquals("slot-one", isolation.live["sid"])

        // Give slot 2 its own cookie and prove the two jars never merge.
        repo.select(2)
        isolation.live["sid"] = "slot-two"
        repo.refreshJar(2)
        assertEquals("slot-two", isolation.stored[2]?.single()?.value)

        repo.select(1)
        assertEquals(setOf("sid"), isolation.live.keys)
        assertEquals("slot-one", isolation.live["sid"])
        assertEquals(
            "slot 1's jar must not have absorbed slot 2's value",
            "slot-two",
            isolation.stored[2]?.single()?.value,
        )
    }

    @Test
    fun `capturing for an inactive slot cannot corrupt the active jar`() = runTest {
        repo.add("Ada")
        repo.add("Ben")
        repo.select(1)
        isolation.live["sid"] = "one"
        // The fake throws if the binding is wrong, which is the invariant this protects.
        assertEquals(1, isolation.capture(1))
        assertEquals(0, isolation.stored[2]?.size ?: 0)
    }

    @Test
    fun `logout clears the jar for that slot only`() = runTest {
        repo.add("Ada")
        repo.add("Ben")
        repo.select(1)
        isolation.live["sid"] = "one"
        repo.refreshJar(1)
        repo.select(2)
        isolation.live["sid"] = "two"
        repo.refreshJar(2)

        repo.logout(2)

        assertEquals(0, repo.find(2)?.cookieCount)
        assertEquals(AccountStatus.NOT_CONNECTED, repo.find(2)?.status)
        assertFalse("logout must delete the stored blob", isolation.stored.containsKey(2))
        assertEquals(1, repo.find(1)?.cookieCount)
    }

    @Test
    fun `removing the selected slot leaves no selection rather than switching behind the user`() = runTest {
        repo.add("Ada")
        repo.add("Ben")
        repo.select(1)
        repo.select(2)

        val result = repo.remove(2)
        assertTrue(result is AccountRepository.RemoveResult.Removed)
        assertNull("the UI must ask, the repository must not guess", repo.selection.value)
        assertNotNull(repo.find(1))
    }

    @Test
    fun `removing an unselected slot keeps the selection untouched`() = runTest {
        repo.add("Ada")
        repo.add("Ben")
        repo.select(2)
        val result = repo.remove(1)
        assertTrue(result is AccountRepository.RemoveResult.Removed)
        assertEquals(2, repo.selection.value)
    }

    @Test
    fun `removal also destroys the slot's stored session`() = runTest {
        repo.add("Ada")
        repo.select(1)
        isolation.live["sid"] = "abc"
        repo.refreshJar(1)
        repo.remove(1)
        assertFalse(isolation.stored.containsKey(1))
        assertTrue(isolation.live.isEmpty())
    }

    @Test
    fun `status only changes through the state machine`() = runTest {
        repo.add("Ada")
        repo.select(1)
        // A page finishing must not mark the account connected.
        val transition = repo.dispatch(1, SessionStateMachine.SessionEvent.LoadFinished)
        assertEquals(AccountStatus.NOT_CONNECTED, transition.status)
        assertEquals(AccountStatus.NOT_CONNECTED, repo.find(1)?.status)

        val authenticated = repo.dispatch(
            1,
            SessionStateMachine.SessionEvent.ProbeAuthenticated(null, clock.nowMillis()),
        )
        assertEquals(AccountStatus.CONNECTED, authenticated.status)
        assertEquals(AccountStatus.CONNECTED, repo.find(1)?.status)
    }

    @Test
    fun `a 401 for a connected slot becomes Session Expired and asks for reauth`() = runTest {
        repo.add("Ada")
        repo.select(1)
        repo.dispatch(1, SessionStateMachine.SessionEvent.ProbeAuthenticated("me@corp", 1L))
        val transition = repo.dispatch(
            1,
            SessionStateMachine.SessionEvent.HttpFailure(com.kartikkamboj.arena.data.model.ErrorKind.UNAUTHORIZED, 401),
        )
        assertEquals(AccountStatus.SESSION_EXPIRED, transition.status)
        assertTrue(transition.has(SessionStateMachine.Effect.PROMPT_RECONNECT))
    }

    @Test
    fun `rate limiting is recorded separately from losing a session`() = runTest {
        repo.add("Ada")
        repo.select(1)
        repo.dispatch(1, SessionStateMachine.SessionEvent.ProbeAuthenticated(null, 1L))
        isolation.live["sid"] = "abc"
        repo.refreshJar(1)

        val transition = repo.dispatch(1, SessionStateMachine.SessionEvent.RateLimited)
        assertEquals(AccountStatus.RATE_LIMITED, transition.status)
        assertFalse("a 429 must never throw a session away", transition.has(SessionStateMachine.Effect.CLEAR_JAR))
        assertEquals(1, repo.find(1)?.cookieCount)
        assertEquals(1, isolation.stored[1]?.size)
        assertEquals(AccountStatus.RATE_LIMITED, repo.find(1)?.status)
    }

    @Test
    fun `labels notes and hints survive a restart`() = runTest {
        repo.add("One")
        repo.rename(1, "Personal")
        repo.setNote(1, "work machine")
        repo.setEmailHint(1, "me@corp.example")
        repo.recordInteraction(1)

        val reopened = newRepo()
        reopened.load()
        val account = reopened.find(1)!!
        assertEquals("Personal", account.label)
        assertEquals("work machine", account.note)
        assertEquals("me@corp.example", account.emailHint)
        assertEquals(1, account.interactionCount)
    }

    @Test
    fun `an empty hint clears the label while an invalid one is ignored`() = runTest {
        repo.add("Ada")
        repo.setEmailHint(1, "me@corp.example")
        repo.setEmailHint(1, "no-at-sign")
        assertEquals("me@corp.example", repo.find(1)?.emailHint)
        repo.setEmailHint(1, "   ")
        assertNull(repo.find(1)?.emailHint)
    }

    @Test
    fun `an invalid rename is rejected without touching the stored label`() = runTest {
        repo.add("Good")
        val reason = repo.rename(1, " ")
        assertNotNull(reason)
        assertEquals("Good", repo.find(1)?.label)
    }

    @Test
    fun `the ttl is enforced by the app because cookie attributes are not observable`() = runTest {
        repo.add("Ada")
        repo.select(1)
        isolation.live["sid"] = "abc"
        repo.applyProbe(
            1,
            AuthProbeResult(observedAtMillis = 1_000L, verdict = ProbeVerdict.AUTHENTICATED, url = "https://arena.ai/"),
        )
        repo.refreshJar(1)
        // Default TTL is 12 h; a day later the stored jar must be treated as expired on its own.
        clock.advanceMillis(24L * 3_600_000L)
        repo.markTtlExpiry()
        assertEquals(AccountStatus.SESSION_EXPIRED, repo.find(1)?.status)
    }

    @Test
    fun `the registry survives a reload and keeps slots distinct`() = runTest {
        repo.add("One")
        repo.add("Two")
        repo.select(2)

        val reopened = newRepo()
        reopened.load()
        assertEquals(2, reopened.accounts.size)
        assertEquals(2, reopened.selection.value)
        assertEquals("Two", reopened.selected()?.label)
    }
}

