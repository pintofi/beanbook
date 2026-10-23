package io.github.pintofi.beanbook.core.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A file saved while a backup is being made must never leave an archive that disagrees with its own
 * manifest: such a backup cannot be restored, and nobody finds out until they need it.
 */
class BackupConsistencyTest {

    private val root: File = Files.createTempDirectory("consistency").toFile()
    private val workspace = ProjectWorkspace(root)
    private val archives = BackupArchive(workspace)
    private val receipt: File

    init {
        workspace.createProject("p", title = "Ledger")
        receipt = File(workspace.projectDir("p"), "documents/receipt.txt").apply {
            parentFile.mkdirs()
            writeText("before")
        }
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun backUp(): Pair<BackupManifest, ByteArray> {
        val out = ByteArrayOutputStream()
        val manifest = archives.write("p", out, appVersion = "0.1.0", projectTitle = "Ledger", now = Instant.EPOCH)
        return manifest to out.toByteArray()
    }

    private fun verifyBytes(bytes: ByteArray): List<ArchiveProblem> {
        val file = File(root, "check.bcaz").apply { writeBytes(bytes) }
        return archives.verify(file, null, null)
    }

    @Test
    fun `a file saved after the manifest was worked out is caught, and the backup starts again`() {
        var calls = 0
        archives.betweenHashingAndCopying = {
            if (calls++ == 0) receipt.writeText("after, and longer")
        }

        val (manifest, bytes) = backUp()

        assertEquals(2, calls, "the first attempt was thrown away")
        assertEquals(emptyList(), verifyBytes(bytes), "the archive agrees with its manifest")
        assertEquals(
            "after, and longer".length.toLong(),
            manifest.files.single { it.path == "documents/receipt.txt" }.size,
        )
        val archive = File(root, "check.bcaz")
        ZipFile(archive).use { zip ->
            val text = zip.getInputStream(zip.getEntry("project/documents/receipt.txt")).readBytes().decodeToString()
            assertEquals("after, and longer", text)
        }
    }

    @Test
    fun `a file that keeps changing gives an error and writes nothing to the destination`() {
        var n = 0
        archives.betweenHashingAndCopying = { receipt.writeText("version ${n++}") }
        val out = ByteArrayOutputStream()

        val error = assertFailsWith<IOException> {
            archives.write("p", out, appVersion = "0.1.0", projectTitle = "Ledger")
        }

        assertTrue(error.message.orEmpty().contains("receipt.txt"), error.message)
        assertEquals(0, out.size(), "nothing reached the destination")
        assertEquals(emptyList(), workspace.cacheDir.listFiles()?.toList().orEmpty(), "no temporary archive left")
    }

    @Test
    fun `an encrypted backup is checked the same way`() {
        val kdf = KeyDerivation { passphrase, salt, _ ->
            java.security.MessageDigest.getInstance("SHA-256").apply {
                update(salt)
                update(String(passphrase).toByteArray())
            }.digest()
        }
        val passphrase = "correct horse".toCharArray()
        var calls = 0
        archives.betweenHashingAndCopying = { if (calls++ == 0) receipt.writeText("changed in flight") }

        val out = ByteArrayOutputStream()
        archives.write("p", out, "0.1.0", projectTitle = "Ledger", passphrase = passphrase, derivation = kdf)
        val file = File(root, "sealed.bcaz").apply { writeBytes(out.toByteArray()) }

        assertEquals(emptyList(), archives.verify(file, passphrase, kdf))
        assertEquals(2, calls)
    }

    @Test
    fun `an ordinary backup still takes one attempt`() {
        var calls = 0
        archives.betweenHashingAndCopying = { calls++ }

        val (_, bytes) = backUp()

        assertEquals(1, calls)
        assertEquals(emptyList(), verifyBytes(bytes))
    }
}
