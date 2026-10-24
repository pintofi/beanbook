package io.github.pintofi.beanbook.core.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Encrypted backups are sealed and opened a chunk at a time, so a large project is never held in
 * memory (#54). These tests cover the chunk edges, the ways a payload can be cut or rearranged,
 * and the archives made before chunking, which must still open.
 */
class ChunkedPayloadCipherTest {

    private val stubKdf = KeyDerivation { passphrase, salt, _ ->
        MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(String(passphrase).toByteArray())
        }.digest()
    }
    private val passphrase get() = "correct horse battery staple".toCharArray()
    private val chunk = ArchiveEncryption.CHUNK_BYTES
    private val sealedChunk = chunk + ArchiveEncryption.TAG_BITS / 8
    private val root: File = Files.createTempDirectory("chunked").toFile()

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun payload(size: Int) = ByteArray(size) { (it * 31 + it / 7).toByte() }

    private fun seal(plain: ByteArray, writeSize: Int = 4096): Pair<ByteArray, ArchiveEncryption.Encrypted> {
        val sealer = PayloadCipher.begin(passphrase, stubKdf, ArchiveEncryption.ARGON2ID)
        val out = ByteArrayOutputStream()
        sealer.wrap(out).use { sink ->
            var at = 0
            while (at < plain.size) {
                val n = minOf(writeSize, plain.size - at)
                sink.write(plain, at, n)
                at += n
            }
        }
        return out.toByteArray() to sealer.encryption
    }

    private fun open(sealed: ByteArray, encryption: ArchiveEncryption.Encrypted): ByteArray {
        val out = ByteArrayOutputStream()
        PayloadCipher.decryptTo(ByteArrayInputStream(sealed), out, passphrase, stubKdf, encryption)
        return out.toByteArray()
    }

    @Test
    fun `payloads round-trip at and around the chunk size`() {
        listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 5, 5 * chunk + 123).forEach { size ->
            val plain = payload(size)
            val (sealed, encryption) = seal(plain)
            assertContentEquals(plain, open(sealed, encryption), "size $size")
            // One tag per chunk, and nothing else added.
            val chunks = maxOf(1, (size + chunk - 1) / chunk)
            assertEquals(size + chunks * 16, sealed.size, "sealed length for $size")
        }
    }

    @Test
    fun `how the payload is written in does not change what comes out`() {
        val plain = payload(3 * chunk + 17)
        val (sealed, encryption) = seal(plain, writeSize = 1)
        assertContentEquals(plain, open(sealed, encryption))
        val (other, otherEncryption) = seal(plain, writeSize = 100_000)
        assertContentEquals(plain, open(other, otherEncryption))
    }

    @Test
    fun `a wrong passphrase is refused`() {
        val (sealed, encryption) = seal(payload(2 * chunk))
        assertFailsWith<PayloadCipher.WrongPassphrase> {
            PayloadCipher.decryptTo(
                ByteArrayInputStream(sealed), ByteArrayOutputStream(), "wrong".toCharArray(), stubKdf, encryption,
            )
        }
    }

    @Test
    fun `a payload cut at a chunk boundary is refused`() {
        val (sealed, encryption) = seal(payload(3 * chunk))
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(sealed.copyOf(2 * sealedChunk), encryption) }
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(sealed.copyOf(sealedChunk), encryption) }
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(ByteArray(0), encryption) }
    }

    @Test
    fun `a payload cut part way through a chunk or extended is refused`() {
        val (sealed, encryption) = seal(payload(2 * chunk + 10))
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(sealed.copyOf(sealed.size - 1), encryption) }
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(sealed + byteArrayOf(0), encryption) }
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(sealed + sealed.copyOf(sealedChunk), encryption) }
    }

    @Test
    fun `chunks swapped, repeated or flipped are refused`() {
        val (sealed, encryption) = seal(payload(3 * chunk))
        val a = sealed.copyOfRange(0, sealedChunk)
        val b = sealed.copyOfRange(sealedChunk, 2 * sealedChunk)
        val c = sealed.copyOfRange(2 * sealedChunk, 3 * sealedChunk)
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(b + a + c, encryption) }
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(a + a + c, encryption) }
        val flipped = sealed.copyOf().also { it[sealedChunk + 100] = (it[sealedChunk + 100].toInt() xor 1).toByte() }
        assertFailsWith<PayloadCipher.WrongPassphrase> { open(flipped, encryption) }
    }

    @Test
    fun `the same payload sealed twice differs`() {
        val plain = payload(chunk)
        assertNotEquals(seal(plain).first.toList(), seal(plain).first.toList())
    }

    private fun assertNotEquals(a: List<Byte>, b: List<Byte>) = kotlin.test.assertNotEquals(a, b)

    @Test
    fun `an archive sealed in one piece before chunking still opens`() {
        val workspace = ProjectWorkspace(root)
        workspace.createProject("p", title = "Old Ledger")
        val archives = BackupArchive(workspace)

        val plain = File(root, "plain.bcaz")
        plain.outputStream().use { archives.write("p", it, appVersion = "0.1.0") }
        val manifest = assertNotNull(archives.peek(plain))

        // Rebuild it the way the app did before #54: an inner zip, sealed with one GCM call.
        val inner = ByteArrayOutputStream()
        ZipFile(plain).use { zip ->
            ZipOutputStream(inner).use { out ->
                manifest.files.forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.path))
                    zip.getInputStream(zip.getEntry(BackupManifest.PROJECT_PREFIX + entry.path)).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        val (ciphertext, encryption) = PayloadCipher.encrypt(
            inner.toByteArray(), passphrase, stubKdf, ArchiveEncryption.ARGON2ID,
        )
        assertEquals(ArchiveEncryption.CIPHER, encryption.cipher)
        val legacy = File(root, "legacy.bcaz")
        ZipOutputStream(legacy.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_NAME))
            zip.write(manifest.copy(encryption = encryption).toJson().write().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupManifest.PAYLOAD_NAME))
            zip.write(ciphertext)
            zip.closeEntry()
        }

        assertEquals(emptyList(), archives.verify(legacy, passphrase, stubKdf))
        assertEquals(
            listOf<ArchiveProblem>(ArchiveProblem.WrongPassphrase),
            archives.verify(legacy, "wrong".toCharArray(), stubKdf),
        )
    }

    @Test
    fun `a chunked archive of a project with a large file verifies and leaves no staging file`() {
        val workspace = ProjectWorkspace(root)
        workspace.createProject("p", title = "Big Ledger")
        File(workspace.projectDir("p"), "documents").mkdirs()
        File(workspace.projectDir("p"), "documents/scan.bin").writeBytes(payload(3 * chunk + 99))
        val archives = BackupArchive(workspace)

        val file = File(root, "big.bcaz")
        file.outputStream().use {
            archives.write("p", it, appVersion = "0.1.0", passphrase = passphrase, derivation = stubKdf)
        }

        assertEquals(emptyList(), archives.verify(file, passphrase, stubKdf))
        assertEquals(emptyList(), workspace.cacheDir.listFiles()?.toList().orEmpty())
        val target = File(root, "restored")
        assertEquals(emptyList(), archives.extract(file, target, passphrase, stubKdf))
        assertContentEquals(payload(3 * chunk + 99), File(target, "documents/scan.bin").readBytes())
    }
}
