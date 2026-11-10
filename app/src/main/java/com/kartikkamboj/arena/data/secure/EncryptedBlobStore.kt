package com.kartikkamboj.arena.data.secure

import com.kartikkamboj.arena.core.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed store for material that must not exist in cleartext on disk: the per-slot cookie
 * jars. Payloads are `[4-byte version][cipher output]`, and a blob that fails to authenticate is
 * deleted rather than trusted — a torn file must never be able to look like a session.
 *
 * Lives under `noBackupFilesDir`, which both the legacy and API 31+ backup rules exclude (see
 * res/xml/backup_rules.xml and data_extraction_rules.xml), so a session cannot ride along into a
 * cloud backup or a device-to-device transfer.
 */
class EncryptedBlobStore(
    private val directory: File,
    private val cipher: BlobCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun write(key: String, text: String): Boolean = withContext(dispatcher) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) {
                error("cannot create ${directory.name}")
            }
            val target = fileFor(key)
            val temp = File(target.parentFile, target.name + ".tmp")
            val payload = VERSION + cipher.encrypt(text.toByteArray(Charsets.UTF_8))
            temp.writeBytes(payload)
            if (!temp.renameTo(target)) {
                temp.delete()
                error("atomic rename failed for $key")
            }
            true
        }.getOrElse { failure ->
            AppLog.w("secure write failed for $key", failure)
            false
        }
    }

    suspend fun read(key: String): String? = withContext(dispatcher) {
        runCatching {
            val target = fileFor(key)
            if (!target.exists()) return@runCatching null
            val payload = target.readBytes()
            if (payload.size < VERSION.size + 1 || !payload.startsWith(VERSION)) {
                AppLog.w("unexpected format in $key, discarding")
                target.delete()
                return@runCatching null
            }
            val plain = cipher.decrypt(payload.copyOfRange(VERSION.size, payload.size))
            if (plain == null) {
                AppLog.w("undecryptable blob for $key, discarding")
                target.delete()
                return@runCatching null
            }
            String(plain, Charsets.UTF_8)
        }.getOrElse { failure ->
            AppLog.w("secure read failed for $key", failure)
            null
        }
    }

    suspend fun delete(key: String): Boolean = withContext(dispatcher) {
        runCatching { fileFor(key).delete() }.getOrElse { false }
    }

    fun contains(key: String): Boolean = fileFor(key).exists()

    /** Size in bytes; used by diagnostics to show retention without revealing content. */
    fun byteSize(key: String): Int = runCatching { fileFor(key).length().toInt() }.getOrDefault(0)

    suspend fun deleteAll(): Int = withContext(dispatcher) {
        runCatching {
            val files = directory.listFiles()?.toList().orEmpty()
            files.count { it.delete() }
        }.getOrDefault(0)
    }

    private fun fileFor(key: String): File = File(directory, sanitise(key) + ".bin")

    private fun sanitise(raw: String): String = raw.map { ch ->
        if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
    }.joinToString("").ifBlank { "blob" }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }

    private companion object {
        /** Format tag; bump it only together with a migration that rewrites every blob. */
        val VERSION: ByteArray = byteArrayOf('A'.code.toByte(), 'S'.code.toByte(), 1, 0)
    }
}
