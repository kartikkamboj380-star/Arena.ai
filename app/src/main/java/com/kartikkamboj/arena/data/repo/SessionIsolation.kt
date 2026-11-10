package com.kartikkamboj.arena.data.repo

import com.kartikkamboj.arena.data.model.CookieEntry

/**
 * What "five separate accounts" has to mean on Android, given what the platform exposes.
 *
 * There is no public API that binds a `WebView` to one of AndroidX's `Profile`s: `ProfileStore`
 * can create, enumerate and wipe profiles, and `WebViewCompat.getProfile()` can read back the
 * profile a view happens to use, but nothing lets an app say "this WebView lives in profile 2".
 * So the isolation this app implements is *temporal* rather than spatial:
 *
 *  * exactly one slot owns the shared cookie jar at any moment;
 *  * switching captures the outgoing jar to an encrypted blob, wipes the jar, and installs the
 *    incoming jar before the next navigation;
 *  * site storage (localStorage / IndexedDB / HTTP cache) is wiped on switch by default, because
 *    leaving it behind would let the next slot read the previous slot's client state.
 *
 * docs/ACCOUNTS.md documents the residue this cannot remove, and what a device supporting
 * `MULTI_PROFILE` additionally gets.
 */
interface SessionIsolation {

    val capability: IsolationCapability

    /** Slot that currently owns the shared cookie jar, or null while nothing is loaded. */
    val activeSlot: Int?

    /** Declares which slot owns the shared jar. Called when a session view is created for it. */
    fun bind(slot: Int)

    /** Forgets the binding when that slot's view is destroyed. */
    fun unbind(slot: Int)

    /** Copy the live shared jar into the slot's encrypted blob. Returns entries stored. */
    suspend fun capture(slot: Int): Int

    /** Install the slot's stored jar into the shared cookie jar (after wiping it). */
    suspend fun restore(slot: Int): RestoreOutcome

    /** Drop both the live jar and the stored blob for this slot. */
    suspend fun release(slot: Int)

    /** Entries currently on disk for a slot, for the diagnostics card. */
    suspend fun storedEntries(slot: Int): List<CookieEntry>

    suspend fun storedCount(slot: Int): Int = storedEntries(slot).size

    /** Called when a page settles so the freshest jar is what gets restored later. */
    suspend fun refreshFromLive(slot: Int): Int

    /** Wipe everything this client can reach (Settings → clear local data). */
    suspend fun wipeAll(): WipeReport
}

data class IsolationCapability(
    val multiProfileSupported: Boolean,
    val profileApiPresent: Boolean,
    val webViewPackage: String?,
    val webViewVersion: String?,
) {
    val summary: String
        get() = when {
            multiProfileSupported ->
                "Per-profile WebView data available; using jar swap plus profile wipe"

            else -> "Cookie-jar swap only (platform cannot bind a WebView to a named profile)"
        }
}

sealed interface RestoreOutcome {
    /** Nothing stored: the slot starts clean, which is the honest "not connected" case. */
    data object NothingStored : RestoreOutcome

    /** Jar installed; [entries] is how many cookies the slot carries. */
    data class Applied(val entries: Int, val expiresAtMillis: Long?) : RestoreOutcome

    /** A jar existed but passed the locally tracked TTL; it was dropped, not installed. */
    data class Expired(val capturedAtMillis: Long, val ttlMillis: Long) : RestoreOutcome

    /** The blob could not be read or authenticated; treated as no session. */
    data class Failed(val reason: String) : RestoreOutcome
}

data class WipeReport(
    val slotsCleared: Int,
    val blobsRemoved: Int,
    val siteStoragePurged: Boolean,
)
