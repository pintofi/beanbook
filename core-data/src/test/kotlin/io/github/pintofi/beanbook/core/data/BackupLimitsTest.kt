package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A backup is untrusted input: `peek` runs before anyone has chosen to restore, and `verify`
 * before anything is written. A small file must not be able to make either allocate a heap's worth.
 */
class BackupLimitsTest {

    private val root: File = Files.createTempDirectory("limits").toFile()
    private val workspace = ProjectWorkspace(root)
    private val archives = BackupArchive(workspace)
    private val stubKdf = KeyDerivation { passphrase, salt, _ ->
        MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(String(passphrase).toByteArray())
        }.digest()
    }
    private val passphrase get() = "correct horse battery staple".toCharArray()

    init {
        workspace.createProject("p", title = "Ledger")
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun sealed(): File = File(root, "sealed.bcaz").also { file ->
        file.outputStream().use {
            archives.write("p", it, appVersion = "0.1.0", projectTitle = "Ledger", passphrase = passphrase, derivation = stubKdf)
        }
    }

    /** [source] with the entry called [name] replaced by [size] bytes of zeros, which deflate to almost nothing. */
    private fun withZeroEntry(source: File, name: String, size: Long): File {
        val out = File(root, "crafted.bcaz")
        ZipFile(source).use { zip ->
            ZipOutputStream(out.outputStream()).use { target ->
                for (entry in zip.entries()) {
                    if (entry.name == name) continue
                    target.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
                target.putNextEntry(ZipEntry(name))
                val zeros = ByteArray(1 shl 20)
                var left = size
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    target.write(zeros, 0, n)
                    left -= n
                }
                target.closeEntry()
            }
        }
        return out
    }

    @Test
    fun `an oversized manifest is refused without being read`() {
        val archive = withZeroEntry(sealed(), BackupManifest.MANIFEST_NAME, BackupArchive.MAX_MANIFEST_BYTES + 1)
        assertTrue(archive.length() < 2_000_000, "the crafted file is small: ${archive.length()}")

        assertNull(archives.peek(archive))
        assertEquals(
            listOf<ArchiveProblem>(ArchiveProblem.TooLarge("manifest", BackupArchive.MAX_MANIFEST_BYTES)),
            archives.verify(archive, passphrase, stubKdf),
        )
    }

    @Test
    fun `an oversized encrypted payload is refused before it is read or decrypted`() {
        val archive = withZeroEntry(sealed(), BackupManifest.PAYLOAD_NAME, BackupArchive.MAX_PAYLOAD_BYTES + 1)
        assertTrue(archive.length() < 2_000_000, "the crafted file is small: ${archive.length()}")

        assertEquals(
            listOf<ArchiveProblem>(ArchiveProblem.TooLarge("encrypted project", BackupArchive.MAX_PAYLOAD_BYTES)),
            archives.verify(archive, passphrase, stubKdf),
        )
        assertEquals(emptyList(), workspace.cacheDir.listFiles()?.toList().orEmpty(), "no staging file is left behind")
    }

    @Test
    fun `an archive with an absurd number of entries is refused`() {
        val archive = File(root, "many.bcaz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            repeat(BackupArchive.MAX_ENTRIES + 1) {
                zip.putNextEntry(ZipEntry("project/$it"))
                zip.closeEntry()
            }
        }
        assertNull(archives.peek(archive))
        assertEquals(
            listOf<ArchiveProblem>(ArchiveProblem.TooLarge("list of files", BackupArchive.MAX_ENTRIES.toLong())),
            archives.verify(archive, null, null),
        )
    }

    @Test
    fun `an ordinary archive is still read, plain and sealed`() {
        assertEquals(emptyList(), archives.verify(sealed(), passphrase, stubKdf))
        val plain = File(root, "plain.bcaz")
        plain.outputStream().use { archives.write("p", it, appVersion = "0.1.0", projectTitle = "Ledger") }
        assertEquals(emptyList(), archives.verify(plain, null, null))
        assertTrue(archives.peek(plain) != null)
    }

    @Test
    fun `the refusal says what was too big and that nothing changed`() {
        val text = ArchiveProblem.TooLarge("manifest", 8L * 1024 * 1024).describe()
        assertTrue("manifest" in text && "8 MiB" in text && "Nothing has been restored" in text, text)
    }
}
