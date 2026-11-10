package com.kartikkamboj.arena.data.secure

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The blob store and the cipher contract, exercised with the software implementation of the same
 * algorithm the Keystore key uses. This is where the on-disk format gets pinned down: a torn,
 * truncated, foreign-key or mislabelled file must fail closed and be deleted, never half-trusted.
 */
class SecureBlobStoreTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun cipher(): SoftwareGcmCipher = SoftwareGcmCipher(Random.nextBytes(32))

    private fun storeAt(directory: File, cipher: BlobCipher = cipher()) =
        EncryptedBlobStore(directory, cipher, kotlinx.coroutines.Dispatchers.IO)

    @Test
    fun `round trips through the cipher`() {
        val key = cipher()
        val plain = "sid=abc123; csrf=def456".toByteArray()
        val payload = key.encrypt(plain)
        assertNotNull(key.decrypt(payload))
        assertArrayEquals(plain, key.decrypt(payload))
    }

    @Test
    fun `each encryption uses a fresh IV so identical text yields different bytes`() {
        val key = cipher()
        val plain = "same input".toByteArray()
        assertFalse(key.encrypt(plain).contentEquals(key.encrypt(plain)))
    }

    @Test
    fun `a flipped bit is rejected by the gcm tag`() {
        val key = cipher()
        val payload = key.encrypt("hello".toByteArray())
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 1).toByte()
        assertNull("tampered payload must not decrypt", key.decrypt(payload))
    }

    @Test
    fun `a truncated payload is rejected`() {
        val key = cipher()
        val payload = key.encrypt("hello".toByteArray())
        assertNull(key.decrypt(payload.copyOfRange(0, payload.size / 2)))
        assertNull(key.decrypt(ByteArray(4)))
    }

    @Test
    fun `a different key cannot read the blob`() {
        val payload = cipher().encrypt("secret".toByteArray())
        assertNull(cipher().decrypt(payload))
    }

    @Test
    fun `the software cipher never claims to be hardware backed`() {
        assertFalse(cipher().hardwareBacked)
        assertEquals("software-aes-gcm", cipher().keyId)
    }

    @Test
    fun `payloads are not written in cleartext`() = runTest {
        val dir = folder.newFolder()
        val store = storeAt(dir)
        val secret = "sid=SUPERSECRETVALUE"
        assertTrue(store.write("cookie-jar-slot1", secret))

        val raw = File(dir, "cookie-jar-slot1.bin").readText(Charsets.ISO_8859_1)
        assertFalse("the session value must not appear on disk", raw.contains("SUPERSECRETVALUE"))
        assertEquals(secret, store.read("cookie-jar-slot1"))
    }

    @Test
    fun `a corrupted file is discarded rather than returned`() = runTest {
        val dir = folder.newFolder()
        val store = storeAt(dir)
        store.write("blob", "value")

        val file = File(dir, "blob.bin")
        file.writeBytes(file.readBytes().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 8).toByte() })

        assertNull(store.read("blob"))
        assertFalse("an unreadable blob must be removed", store.contains("blob"))
    }

    @Test
    fun `a file with the wrong version tag is discarded`() = runTest {
        val dir = folder.newFolder()
        File(dir, "blob.bin").writeText("not our format at all")
        val store = storeAt(dir)
        assertNull(store.read("blob"))
        assertFalse(store.contains("blob"))
    }

    @Test
    fun `key names are sanitised so a path cannot be smuggled in`() = runTest {
        val dir = folder.newFolder()
        val store = storeAt(dir)
        assertTrue(store.write("../../etc/passwd", "x"))
        assertEquals("x", store.read("../../etc/passwd"))
        assertFalse(File(dir.parentFile, "etc").exists())
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".bin") })
    }

    @Test
    fun `delete and deleteAll remove only what the app wrote`() = runTest {
        val dir = folder.newFolder()
        val store = storeAt(dir)
        store.write("a", "1")
        store.write("b", "2")
        assertTrue(store.contains("a"))
        assertTrue(store.delete("a"))
        assertFalse(store.contains("a"))
        assertEquals(1, store.deleteAll())
        assertFalse(store.contains("b"))
    }

    @Test
    fun `byteSize reports retention without exposing content`() = runTest {
        val dir = folder.newFolder()
        val store = storeAt(dir)
        assertEquals(0, store.byteSize("missing"))
        store.write("blob", "some value")
        assertTrue(store.byteSize("blob") > "some value".toByteArray().size)
    }

    @Test
    fun `reading a missing key is not an error`() = runTest {
        assertNull(storeAt(folder.newFolder()).read("nothing"))
    }
}
