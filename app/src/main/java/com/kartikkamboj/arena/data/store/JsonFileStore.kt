package com.kartikkamboj.arena.data.store

import com.kartikkamboj.arena.core.AppLog
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Plain (unencrypted) private-file JSON persistence for data that is not secret: the account
 * registry, thread pointers and the model catalog. Credentials and cookie jars never go through
 * here — see [com.kartikkamboj.arena.data.secure.EncryptedBlobStore].
 *
 * Writes are temp-file + rename, and a file that cannot be decoded is moved to `.corrupt`
 * instead of being silently replaced, so a bad upgrade is diagnosable instead of lossy.
 */
class JsonFileStore(
    private val directory: File,
    private val json: Json,
) {

    fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val file = File(directory, "$name.json")
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrElse { failure ->
            AppLog.w("could not decode $name; keeping it aside", failure)
            runCatching { file.renameTo(File(directory, "$name.corrupt")) }
            null
        }
    }

    fun <T> write(name: String, serializer: KSerializer<T>, value: T): Boolean {
        return runCatching {
            if (!directory.exists() && !directory.mkdirs()) error("cannot create ${directory.path}")
            val text = json.encodeToString(serializer, value)
            val target = File(directory, "$name.json")
            val temp = File(directory, "$name.json.tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                temp.delete()
                error("rename failed for $name")
            }
            true
        }.getOrElse { failure ->
            AppLog.w("could not persist $name", failure)
            false
        }
    }

    fun delete(name: String): Boolean = runCatching { File(directory, "$name.json").delete() }
        .getOrDefault(false)

    fun contains(name: String): Boolean = File(directory, "$name.json").exists()

    fun byteSize(name: String): Int =
        runCatching { File(directory, "$name.json").length().toInt() }.getOrDefault(0)
}

object JsonConfig {
    val Default: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }
}
