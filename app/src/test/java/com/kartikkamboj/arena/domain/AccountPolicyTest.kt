package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.ArenaAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slot policy: five slots maximum, labels validated before anything is written, and a selection
 * that only ever changes because the user made it change.
 */
class AccountPolicyTest {

    private fun account(
        slot: Int,
        status: AccountStatus = AccountStatus.NOT_CONNECTED,
        cookieCount: Int = 0,
        label: String = "Account $slot",
    ): ArenaAccount = ArenaAccount(
        slot = slot,
        label = label,
        status = status,
        cookieCount = cookieCount,
        snapshotCreatedAtMillis = if (cookieCount > 0) 1L else 0L,
    )

    @Test
    fun `the ceiling is five and a sixth is refused rather than evicting`() {
        val five = (1..5).map { account(it) }
        val outcome = AccountPolicy.add(five, "Sixth", 0L)
        assertTrue("a sixth account must be rejected, not replace an existing one", outcome is AccountPolicy.AddOutcome.Full)
        assertEquals(AccountPolicy.MAX_ACCOUNTS, (outcome as AccountPolicy.AddOutcome.Full).limit)
        assertEquals(5, five.size)
    }

    @Test
    fun `slots are allocated by lowest free number so removal cannot shift identities`() {
        val remaining = listOf(account(1), account(3), account(5))
        assertEquals(2, AccountPolicy.firstFreeSlot(remaining))
        val outcome = AccountPolicy.add(remaining, "Rebuilt", 0L)
        assertTrue(outcome is AccountPolicy.AddOutcome.Created)
        assertEquals(2, (outcome as AccountPolicy.AddOutcome.Created).slot)
    }

    @Test
    fun `a full slot list leaves no free slot`() {
        assertNull(AccountPolicy.firstFreeSlot((1..5).map { account(it) }))
    }

    @Test
    fun `labels are validated for length and injected markup`() {
        val invalid = listOf("", " ", "a", "x".repeat(AccountPolicy.MAX_LABEL_LENGTH + 1), "a<b>c", "line\nbreak")
        invalid.forEach { label ->
            assertTrue("'$label' must be rejected", AccountPolicy.validateLabel(label) != null)
        }
        assertNull(AccountPolicy.validateLabel("Personal"))
        assertNull(AccountPolicy.validateLabel("  Work  "))
    }

    @Test
    fun `a blank label falls back to the slot default instead of being stored empty`() {
        // A blank is *invalid* rather than silently renamed, so the UI can show why.
        assertTrue(AccountPolicy.validateLabel("  ") != null)
        assertEquals("Account 4", AccountPolicy.defaultLabel(4))
    }

    @Test
    fun `creating an account through the policy stores the trimmed label`() {
        val outcome = AccountPolicy.add(emptyList(), "  Work  ", 123L)
        assertTrue(outcome is AccountPolicy.AddOutcome.Created)
        val created = (outcome as AccountPolicy.AddOutcome.Created).account
        assertEquals("Work", created.label)
        assertEquals(1, created.slot)
        assertEquals(123L, created.createdAtMillis)
        assertEquals(AccountStatus.NOT_CONNECTED, created.status)
        assertFalse("a new slot has no session", created.hasStoredSession)
    }

    @Test
    fun `removing the selected slot asks the user instead of picking a successor`() {
        val remaining = listOf(account(2), account(3))
        val decision = AccountPolicy.selectionAfterRemoval(selectedSlot = 1, removedSlot = 1, remaining = remaining)
        assertEquals(AccountPolicy.SelectionDecision.NeedsUserChoice, decision)
    }

    @Test
    fun `removing an unselected slot keeps the selection where it is`() {
        val remaining = listOf(account(1), account(2))
        val decision = AccountPolicy.selectionAfterRemoval(selectedSlot = 1, removedSlot = 2, remaining = remaining)
        assertEquals(AccountPolicy.SelectionDecision.Unchanged, decision)
    }

    @Test
    fun `removing the last account leaves no selection at all`() {
        val decision = AccountPolicy.selectionAfterRemoval(selectedSlot = 1, removedSlot = 1, remaining = emptyList())
        assertEquals(AccountPolicy.SelectionDecision.NeedsUserChoice, decision)
    }

    @Test
    fun `the first account is the only one ever auto-selected`() {
        assertEquals(
            AccountPolicy.SelectionDecision.Select(1),
            AccountPolicy.selectionAfterFirstAdd(listOf(account(1))),
        )
        assertEquals(
            AccountPolicy.SelectionDecision.Unchanged,
            AccountPolicy.selectionAfterFirstAdd(listOf(account(1), account(2))),
        )
    }

    @Test
    fun `only verified slots are usable and the summary distinguishes stored from live`() {
        val accounts = listOf(
            account(1, AccountStatus.CONNECTED, cookieCount = 3),
            account(2, AccountStatus.SESSION_EXPIRED, cookieCount = 2),
            account(3, AccountStatus.LOGIN_REQUIRED, cookieCount = 0),
            account(4, AccountStatus.RATE_LIMITED, cookieCount = 4),
        )
        // Slot 4 is throttled but still holds an unverified jar, and only slot 1 is *verified*.
        assertEquals("1 of 4 verified · 2 with an unverified stored session", AccountPolicy.statusSummary(accounts))

        val slots = AccountPolicy.usableSlots(accounts)
        assertEquals(
            "only a positive probe marks a slot usable - a 429 does not, and neither does a stored jar",
            listOf(1),
            slots,
        )
    }

    @Test
    fun `a stored jar alone is not a usable slot`() {
        val storedOnly = listOf(account(1, AccountStatus.NOT_CONNECTED, cookieCount = 5))
        assertTrue(AccountPolicy.usableSlots(storedOnly).isEmpty())
    }
}
