package com.kartikkamboj.arena.data.repo

import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.data.model.AccountRegistry
import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.ArenaAccount
import com.kartikkamboj.arena.data.model.AuthProbeResult
import com.kartikkamboj.arena.data.model.ProbeVerdict
import com.kartikkamboj.arena.data.prefs.SettingsSource
import com.kartikkamboj.arena.data.store.JsonFileStore
import com.kartikkamboj.arena.domain.AccountPolicy
import com.kartikkamboj.arena.domain.SessionStateMachine
import com.kartikkamboj.arena.domain.SessionStateMachine.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owner of the five account slots and of every status change they go through.
 *
 * Two things are concentrated here on purpose: the ceiling ("up to five", rejected rather than
 * evicted) and the rule that a status only changes through [SessionStateMachine]. The repository
 * never sets `CONNECTED` itself, and it never rewrites cookies — it asks [SessionIsolation] to do
 * that and records what came back.
 */
class AccountRepository(
    private val store: JsonFileStore,
    private val isolation: SessionIsolation,
    private val settings: SettingsSource,
    private val clock: Clock,
    @Suppress("unused") private val scope: CoroutineScope,
) {

    private val mutex = Mutex()

    private val _registry = MutableStateFlow(AccountRegistry())
    val registry: StateFlow<AccountRegistry> = _registry.asStateFlow()

    /** Emits whenever the *selected* slot changed, so the session layer can react. */
    private val _selection = MutableStateFlow<Int?>(null)
    val selection: StateFlow<Int?> = _selection.asStateFlow()

    val accounts: List<ArenaAccount> get() = _registry.value.accounts

    fun selected(): ArenaAccount? = _registry.value.selectedSlot?.let { slot ->
        accounts.firstOrNull { it.slot == slot }
    }

    fun find(slot: Int): ArenaAccount? = accounts.firstOrNull { it.slot == slot }

    fun isFull(): Boolean = accounts.size >= AccountPolicy.MAX_ACCOUNTS

    suspend fun load() = mutex.withLock {
        val persisted = store.read(NAME, AccountRegistry.serializer()) ?: AccountRegistry()
        val now = clock.nowMillis()
        val config = config()
        val hydrated = persisted.accounts.map { account ->
            val stored = isolation.storedCount(account.slot)
            val age = if (account.snapshotCreatedAtMillis > 0L) {
                now - account.snapshotCreatedAtMillis
            } else {
                Long.MAX_VALUE
            }
            val boot = SessionStateMachine.bootStatus(stored > 0, age, config)
            account.copy(
                // A stored jar is never presented as a live login; see bootStatus.
                status = boot,
                cookieCount = stored,
                lastActiveAtMillis = account.lastActiveAtMillis,
            )
        }
        val selectedSlot = persisted.selectedSlot?.takeIf { slot -> hydrated.any { it.slot == slot } }
        _registry.value = persisted.copy(
            version = AccountRegistry.CURRENT_VERSION,
            selectedSlot = selectedSlot,
            accounts = hydrated,
        )
        _selection.value = selectedSlot
        AppLog.d("loaded ${hydrated.size} account slot(s), selected=$selectedSlot")
    }

    private fun config() = SessionStateMachine.Config(
        sessionTtlMillis = settings.settings.value.sessionTtlMillis,
        autoReprobeOnLoad = settings.settings.value.autoReprobe,
    )

    /** Creates a slot. Never activates it: the user has to select a slot explicitly. */
    suspend fun add(rawLabel: String): AccountPolicy.AddOutcome {
        val now = clock.nowMillis()
        return mutex.withLock {
            when (val outcome = AccountPolicy.add(_registry.value.accounts, rawLabel, now)) {
                is AccountPolicy.AddOutcome.Created -> {
                    _registry.value = _registry.value.copy(
                        accounts = _registry.value.accounts + outcome.account,
                    )
                    persistLocked()
                    AppLog.d("created slot ${outcome.slot}")
                    outcome
                }

                else -> outcome
            }
        }
    }

    /**
     * Moves the app to another slot. The outgoing jar is captured first, so nothing is lost if the
     * incoming slot turns out to have no session.
     */
    suspend fun select(slot: Int): SelectOutcome {
        val target = find(slot) ?: return SelectOutcome.UnknownSlot(slot)
        val current = _registry.value.selectedSlot
        if (current == slot) return SelectOutcome.AlreadySelected(slot)

        if (current != null) {
            val captured = runCatching { isolation.capture(current) }.getOrDefault(0)
            updateSlot(current) { it.copy(cookieCount = captured) }
        }
        val restore = runCatching { isolation.restore(slot) }
            .getOrElse { failure ->
                AppLog.w("isolation restore failed", failure)
                RestoreOutcome.Failed(failure.javaClass.simpleName)
            }
        mutex.withLock {
            _registry.value = _registry.value.copy(selectedSlot = slot)
            _selection.value = slot
            persistLocked()
        }
        val status = when (restore) {
            is RestoreOutcome.Applied ->
                if (target.isAuthenticated) AccountStatus.CONNECTED else AccountStatus.NOT_CONNECTED

            is RestoreOutcome.Expired -> AccountStatus.SESSION_EXPIRED
            RestoreOutcome.NothingStored -> AccountStatus.NOT_CONNECTED
            is RestoreOutcome.Failed -> AccountStatus.CONNECTION_ERROR
        }
        updateSlot(slot) { account ->
            account.copy(
                status = status,
                cookieCount = (restore as? RestoreOutcome.Applied)?.entries ?: 0,
                lastActiveAtMillis = clock.nowMillis(),
            )
        }
        AppLog.d("selected slot $slot, restore=$restore")
        return SelectOutcome.Success(slot, restore)
    }

    sealed interface SelectOutcome {
        data class Success(val slot: Int, val restore: RestoreOutcome) : SelectOutcome
        data class AlreadySelected(val slot: Int) : SelectOutcome
        data class UnknownSlot(val slot: Int) : SelectOutcome
    }

    /** The only way status changes. Returns the transition so callers can run its effects. */
    suspend fun dispatch(slot: Int, event: SessionStateMachine.SessionEvent): Transition {
        val account = find(slot)
            ?: return Transition(
                status = AccountStatus.NOT_CONNECTED,
                effects = setOf(SessionStateMachine.Effect.NONE),
                reason = "no such slot $slot",
            )
        val transition = SessionStateMachine.transition(
            current = account.status,
            event = event,
            hadStoredSession = account.cookieCount > 0,
            config = config(),
        )
        if (transition.status != account.status) {
            AppLog.d("slot $slot ${account.status.name} -> ${transition.status.name} (${transition.reason})")
        }
        updateSlot(slot) { it.copy(status = transition.status) }
        return transition
    }

    /** Fold a probe verdict into the machine, and remember what was observed. */
    suspend fun applyProbe(slot: Int, result: AuthProbeResult): Transition {
        val event = when (result.verdict) {
            ProbeVerdict.AUTHENTICATED -> SessionStateMachine.SessionEvent.ProbeAuthenticated(
                label = result.accountLabel,
                atMillis = result.observedAtMillis,
            )

            ProbeVerdict.ANONYMOUS -> SessionStateMachine.SessionEvent.ProbeAnonymous(
                result.observedAtMillis,
            )

            ProbeVerdict.UNKNOWN -> SessionStateMachine.SessionEvent.ProbeInconclusive(
                result.observedAtMillis,
            )
        }
        val transition = dispatch(slot, event)
        // Read the stored count *here*: the update lambda below is not a suspend block, and the
        // count must reflect the jar as it was when the probe ran.
        val storedCount = runCatching { isolation.storedCount(slot) }.getOrDefault(0)
        updateSlot(slot) { account ->
            account.copy(
                lastProbeAtMillis = result.observedAtMillis,
                lastProbeOk = result.verdict == ProbeVerdict.AUTHENTICATED,
                observedLabel = result.accountLabel ?: account.observedLabel,
                cookieCount = if (storedCount > 0) storedCount else account.cookieCount,
                snapshotCreatedAtMillis = if (result.verdict == ProbeVerdict.AUTHENTICATED) {
                    result.observedAtMillis
                } else {
                    account.snapshotCreatedAtMillis
                },
            )
        }
        return transition
    }

    /** Persist the live jar for a slot without changing its status (used on page settle). */
    suspend fun refreshJar(slot: Int) {
        val count = runCatching { isolation.refreshFromLive(slot) }.getOrDefault(0)
        updateSlot(slot) { it.copy(cookieCount = count) }
    }

    suspend fun logout(slot: Int) {
        val transition = dispatch(slot, SessionStateMachine.SessionEvent.LoggedOut)
        if (settings.settings.value.wipeJarOnLogout ||
            transition.has(SessionStateMachine.Effect.CLEAR_JAR)
        ) {
            runCatching { isolation.release(slot) }
                .onFailure { AppLog.w("logout wipe failed for slot $slot", it) }
        }
        updateSlot(slot) { account ->
            account.copy(cookieCount = 0, snapshotCreatedAtMillis = 0L, observedLabel = null)
        }
        AppLog.d("slot $slot logged out; ${AccountPolicy.MAX_ACCOUNTS - accounts.size} slot(s) free")
    }

    /**
     * Removes a slot. The selection is *not* moved to another slot automatically: the caller ends up
     * with `selectedSlot == null` and must ask the user, so the visible account can never change by
     * accident.
     */
    suspend fun remove(slot: Int): RemoveResult {
        val target = find(slot) ?: return RemoveResult.Unknown(slot)
        runCatching { isolation.release(slot) }
            .onFailure { AppLog.w("could not clear jar for removed slot $slot", it) }
        return mutex.withLock {
            val remaining = _registry.value.accounts.filterNot { it.slot == slot }
            val decision = AccountPolicy.selectionAfterRemoval(
                selectedSlot = _registry.value.selectedSlot,
                removedSlot = slot,
                remaining = remaining,
            )
            val newSelected = when (decision) {
                is AccountPolicy.SelectionDecision.Select -> decision.slot
                AccountPolicy.SelectionDecision.Unchanged -> _registry.value.selectedSlot
                AccountPolicy.SelectionDecision.NeedsUserChoice -> null
            }
            _registry.value = _registry.value.copy(accounts = remaining, selectedSlot = newSelected)
            _selection.value = newSelected
            persistLocked()
            AppLog.d("removed slot $slot (${target.label}); selection now $newSelected")
            RemoveResult.Removed(slot, newSelected, remaining.size)
        }
    }

    sealed interface RemoveResult {
        data class Removed(val slot: Int, val selectedSlot: Int?, val remaining: Int) : RemoveResult
        data class Unknown(val slot: Int) : RemoveResult
    }

    /** Returns a validation message when the label is unusable, null on success. */
    suspend fun rename(slot: Int, rawLabel: String): String? {
        val invalid = AccountPolicy.validateLabel(rawLabel)
        if (invalid != null) return invalid
        updateSlot(slot) { it.copy(label = rawLabel.trim()) }
        return null
    }

    suspend fun setNote(slot: Int, note: String) {
        updateSlot(slot) { it.copy(note = note.take(400)) }
    }

    /**
     * [hint] is a local label only. A blank value clears it; a value that is not shaped like an
     * e-mail address is ignored, so a typo cannot silently erase the identifier the user typed.
     */
    suspend fun setEmailHint(slot: Int, hint: String?) {
        val trimmed = hint?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            updateSlot(slot) { it.copy(emailHint = null) }
            return
        }
        if (!trimmed.contains('@') || trimmed.length > 120) return
        updateSlot(slot) { it.copy(emailHint = trimmed) }
    }

    suspend fun recordInteraction(slot: Int) {
        updateSlot(slot) { account ->
            account.copy(
                interactionCount = account.interactionCount + 1,
                lastActiveAtMillis = clock.nowMillis(),
            )
        }
    }

    suspend fun markTtlExpiry() {
        val now = clock.nowMillis()
        val config = config()
        accounts.filter { it.snapshotCreatedAtMillis > 0L }.forEach { account ->
            val age = now - account.snapshotCreatedAtMillis
            if (age > config.sessionTtlMillis) {
                dispatch(account.slot, SessionStateMachine.SessionEvent.SessionAgedOut(now))
            }
        }
    }

    private suspend fun updateSlot(slot: Int, transform: (ArenaAccount) -> ArenaAccount) {
        mutex.withLock {
            val updated = _registry.value.accounts.map { account ->
                if (account.slot == slot) transform(account) else account
            }
            _registry.value = _registry.value.copy(accounts = updated)
            persistLocked()
        }
    }

    private fun persistLocked() {
        store.write(NAME, AccountRegistry.serializer(), _registry.value)
    }

    private companion object {
        const val NAME = "accounts"
    }
}
