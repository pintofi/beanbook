package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The encryption envelope: the manifest, the cipher and every way an archive can refuse.
 *
 * The key derivation itself is Argon2id, which is native code that only exists on Android;
 * it is exercised on a device in `app/src/androidTest`. What is tested here is everything
 * around it, with a stand-in derivation — because a bug in the envelope loses data just as
 * thoroughly as a bug in the KDF, and this half can be tested in milliseconds.
 */
class ArchiveEncryptionTest {

    private val root: File = Files.createTempDirectory("encrypted").toFile()
    private val workspace = ProjectWorkspace(root)
    private val archives = BackupArchive(workspace)

    /**
     * A deterministic stand-in for Argon2id. **Not** a KDF: no cost, no memory hardness. It
     * exists so the envelope can be tested without native code, and it is never used by the
     * app — `Argon2idKeyDerivation` is.
     */
    private val stubKdf = KeyDerivation { passphrase, salt, _ ->
        MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(String(passphrase).toByteArray())
        }.digest()
    }

    private val passphrase get() = "correct horse battery staple".toCharArray()

    init {
        workspace.createProject("p", title = "Secret Ledger")
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun encrypted(name: String = "sealed.bcaz"): File {
        val file = File(root, name)
        file.outputStream().use {
            archives.write(
                projectId = "p",
                out = it,
                appVersion = "0.1.0",
                projectTitle = "Secret Ledger",
                passphrase = passphrase,
                derivation = stubKdf,
            )
        }
        return file
    }

    @Test
    fun `an encrypted archive round-trips to the same bytes`() {
        val archive = encrypted()
        val target = File(root, "restored")

        assertEquals(emptyList(), archives.extract(archive, target, passphrase, stubKdf))

        val original = workspace.projectDir("p")
        val paths = target.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(target).path }.sorted().toList()
        assertTrue(paths.isNotEmpty())
        paths.forEach { path ->
            assertEquals(
                File(original, path).readBytes().toList(),
                File(target, path).readBytes().toList(),
                "$path differs after an encrypted round trip",
            )
        }
    }

    @Test
    fun `the manifest stays readable, so an archive can be identified without the passphrase`() {
        val archive = encrypted()

        val manifest = assertNotNull(archives.peek(archive))

        assertEquals("Secret Ledger", manifest.projectTitle)
        assertEquals("p", manifest.projectId)
        assertTrue(manifest.files.isNotEmpty(), "the file list must still be legible")
        assertIs<ArchiveEncryption.Encrypted>(manifest.encryption)
    }

    @Test
    fun `the ledger itself is not readable in the archive`() {
        // The point of the exercise: no account name, payee or amount in the clear.
        val marker = "Assets:Bank:Checking"
        File(workspace.projectDir("p"), "files/transactions.beancount")
            .appendText("\n2026-08-24 * \"Secret payee\"\n  $marker  1.00 USD\n  Expenses:Rent\n")

        val bytes = encrypted("no-plaintext.bcaz").readBytes().decodeToString(throwOnInvalidSequence = false)

        assertFalse(bytes.contains(marker), "an account name was readable in the archive")
        assertFalse(bytes.contains("Secret payee"), "a payee was readable in the archive")
    }

    @Test
    fun `file names are hidden too, not just their contents`() {
        val archive = encrypted()

        val names = ZipFile(archive).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }

        // Only the manifest and one sealed blob. The shape of someone's project — how many
        // files, what they are called — is as revealing as the amounts inside them.
        assertEquals(listOf("manifest.json", "project.enc").sorted(), names.sorted())
    }

    @Test
    fun `a wrong passphrase is refused, and says so plainly`() {
        val archive = encrypted()

        val problem = archives.verify(archive, "not the passphrase".toCharArray(), stubKdf).single()

        assertEquals(ArchiveProblem.WrongPassphrase, problem)
        assertTrue(problem.describe().contains("Nothing has been restored"), problem.describe())
    }

    @Test
    fun `no passphrase at all asks for one instead of failing obscurely`() {
        val archive = encrypted()

        val problem = archives.verify(archive).single()

        assertEquals(ArchiveProblem.PassphraseNeeded, problem)
        assertTrue(
            problem.describe().contains("nobody, including us, can open it"),
            problem.describe(),
        )
    }

    @Test
    fun `a wrong passphrase extracts nothing`() {
        val archive = encrypted()
        val target = File(root, "not-restored")

        val problems = archives.extract(archive, target, "wrong".toCharArray(), stubKdf)

        assertEquals(listOf(ArchiveProblem.WrongPassphrase), problems)
        assertFalse(target.exists())
    }

    @Test
    fun `a tampered payload fails the authentication tag rather than decrypting to rubbish`() {
        val archive = encrypted()
        val tampered = File(root, "tampered.bcaz")
        val bytes = archive.readBytes()
        // Flip a byte inside the sealed blob; GCM's tag is what makes this loud.
        val offset = bytes.size / 2
        bytes[offset] = (bytes[offset].toInt() xor 0x40).toByte()
        tampered.writeBytes(bytes)

        val problems = archives.verify(tampered, passphrase, stubKdf)

        assertTrue(problems.isNotEmpty(), "a tampered payload was accepted")
        assertTrue(
            problems.single() is ArchiveProblem.WrongPassphrase ||
                problems.single() is ArchiveProblem.Unreadable,
            "unexpected problem: ${problems.single()}",
        )
    }

    @Test
    fun `no temporary plaintext is left behind after a decrypt`() {
        val archive = encrypted()

        archives.verify(archive, passphrase, stubKdf)

        val strays = root.listFiles()?.filter { it.name.startsWith("payload") }.orEmpty()
        assertTrue(strays.isEmpty(), "decrypted payload left on disk: $strays")
    }

    @Test
    fun `salt and nonce differ between two backups of the same project`() {
        val first = assertNotNull(archives.peek(encrypted("one.bcaz")))
        val second = assertNotNull(archives.peek(encrypted("two.bcaz")))

        val a = assertIs<ArchiveEncryption.Encrypted>(first.encryption)
        val b = assertIs<ArchiveEncryption.Encrypted>(second.encryption)
        assertFalse(a.salt.contentEquals(b.salt), "the salt must not repeat")
        assertFalse(a.nonce.contentEquals(b.nonce), "a GCM nonce must never repeat under one key")
    }

    @Test
    fun `the cost parameters travel with the archive, so it opens after the defaults change`() {
        val file = File(root, "costly.bcaz")
        file.outputStream().use {
            archives.write(
                projectId = "p",
                out = it,
                appVersion = "0.1.0",
                passphrase = passphrase,
                derivation = stubKdf,
                cost = KdfCost(iterations = 7, memoryKib = 8 * 1024, parallelism = 1),
            )
        }

        val encryption = assertIs<ArchiveEncryption.Encrypted>(
            assertNotNull(archives.peek(file)).encryption,
        )
        assertEquals(7, encryption.iterations)
        assertEquals(8 * 1024, encryption.memoryKib)
        assertEquals(1, encryption.parallelism)
        assertEquals("argon2id", encryption.kdf)
        assertEquals("AES-256-GCM-CHUNKED", encryption.cipher)
    }

    @Test
    fun `a plain archive still needs no passphrase`() {
        val plain = File(root, "plain.bcaz")
        plain.outputStream().use { archives.write("p", it, appVersion = "0.1.0") }

        assertEquals(emptyList(), archives.verify(plain))
        assertEquals(ArchiveEncryption.None, assertNotNull(archives.peek(plain)).encryption)
    }

    @Test
    fun `encryption details survive a manifest round trip`() {
        val encryption = ArchiveEncryption.Encrypted(
            kdf = "argon2id",
            salt = ByteArray(16) { it.toByte() },
            nonce = ByteArray(12) { (it * 2).toByte() },
            iterations = 3,
            memoryKib = 65536,
            parallelism = 2,
        )

        val parsed = ArchiveEncryption.fromJson(Json.parse(encryption.toJson().write()))

        assertEquals(encryption, parsed)
    }
}
