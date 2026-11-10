package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.ArenaAccount

/**
 * Rules for the five account slots, kept out of the repository so they can be asserted directly.
 *
 * The two hard product rules encoded here are: the ceiling is five (no silent eviction), and the
 * selected slot never changes behind the user's back.
 */
object AccountPolicy {

    const val MAX_ACCOUNTS = 5
    const val MIN_LABEL_LENGTH = 2
    const val MAX_LABEL_LENGTH = 24

    sealed interface AddOutcome {
        data class Created(val account: ArenaAccount, val slot: Int) : AddOutcome
        data class Full(val limit: Int) : AddOutcome
        data class InvalidLabel(val reason: String) : AddOutcome
    }

    sealed interface SelectionDecision {
        /** Keep the current selection. */
        data object Unchanged : SelectionDecision

        /** The selected slot disappeared: the user must choose, nothing is auto-picked. */
        data object NeedsUserChoice : SelectionDecision

        /** Point the app at a specific slot (used only for the very first account). */
        data class Select(val slot: Int) : SelectionDecision
    }

    fun defaultLabel(slot: Int): String = "Account $slot"

    fun validateLabel(raw: String): String? {
        val label = raw.trim()
        return when {
            label.length < MIN_LABEL_LENGTH -> "Use at least $MIN_LABEL_LENGTH characters."
            label.length > MAX_LABEL_LENGTH -> "Keep it under $MAX_LABEL_LENGTH characters."
            label.any { it == '<' || it == '>' || it == '\n' || it == '\t' } ->
                "No angle brackets or line breaks."

            else -> null
        }
    }

    fun firstFreeSlot(accounts: List<ArenaAccount>): Int? =
        (1..MAX_ACCOUNTS).firstOrNull { slot -> accounts.none { it.slot == slot } }

    fun add(accounts: List<ArenaAccount>, rawLabel: String, now: Long): AddOutcome {
        if (accounts.size >= MAX_ACCOUNTS) return AddOutcome.Full(MAX_ACCOUNTS)
        val invalid = validateLabel(rawLabel)
        if (invalid != null) return AddOutcome.InvalidLabel(invalid)
        val slot = firstFreeSlot(accounts) ?: return AddOutcome.Full(MAX_ACCOUNTS)
        val label = rawLabel.trim().ifBlank { defaultLabel(slot) }
        return AddOutcome.Created(
            account = ArenaAccount(
                slot = slot,
                label = label,
                createdAtMillis = now,
            ),
            slot = slot,
        )
    }

    fun selectionAfterRemoval(
        selectedSlot: Int?,
        removedSlot: Int,
        remaining: List<ArenaAccount>,
    ): SelectionDecision = when {
        selectedSlot != removedSlot && selectedSlot != null &&
            remaining.any { it.slot == selectedSlot } -> SelectionDecision.Unchanged

        remaining.isEmpty() -> SelectionDecision.NeedsUserChoice
        selectedSlot == removedSlot -> SelectionDecision.NeedsUserChoice
        else -> SelectionDecision.Unchanged
    }

    fun selectionAfterFirstAdd(accounts: List<ArenaAccount>): SelectionDecision =
        if (accounts.size == 1) accounts.first().let { SelectionDecision.Select(it.slot) }
        else SelectionDecision.Unchanged

    /** Slots that may be logged into Arena at the same time, from the app's point of view. */
    fun usableSlots(accounts: List<ArenaAccount>): List<Int> =
        accounts.filter { it.isAuthenticated }.map { it.slot }

    fun statusSummary(accounts: List<ArenaAccount>): String {
        val connected = accounts.count { it.isAuthenticated }
        val stored = accounts.count { it.hasStoredSession && !it.isAuthenticated }
        return "$connected of ${accounts.size} verified" +
            if (stored > 0) " · $stored with an unverified stored session" else ""
    }
}
