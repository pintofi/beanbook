package io.github.pintofi.beanbook.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.backup.Argon2idKeyDerivation
import io.github.pintofi.beanbook.app.backup.BackupController
import io.github.pintofi.beanbook.core.data.ArchiveEncryption
import io.github.pintofi.beanbook.core.data.ArchiveProblem
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.KdfCost
import io.github.pintofi.beanbook.core.data.ProjectRegistry
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Argon2id, on a real device.
 *
 * `ArchiveEncryptionTest` covers the envelope with a stand-in derivation; this covers the
 * part that stand-in cannot: native Argon2id, at the cost parameters the app actually ships,
 * on the hardware it ships to. It also measures how long that takes, because a key
 * derivation nobody can wait for is a feature nobody will use.
 */
@RunWith(AndroidJUnit4::class)
class Argon2BackupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "argon2-backup-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val workspace = ProjectWorkspace(root)
    private val archives = BackupArchive(workspace)
    private val derivation = Argon2idKeyDerivation()

    private val passphrase get() = "a passphrase someone might actually use".toCharArray()

    init {
        workspace.createProject("p", title = "Encrypted Ledger")
    }

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(name: String, cost: KdfCost = KdfCost()): File {
        val file = File(root, name)
        file.outputStream().use {
            archives.write(
                projectId = "p",
                out = it,
                appVersion = "0.1.0",
                projectTitle = "Encrypted Ledger",
                passphrase = passphrase,
                derivation = derivation,
                cost = cost,
            )
        }
        return file
    }

    @Test fun anArchiveEncryptedWithArgon2idRoundTrips() {
        val archive = write("sealed.bcaz")
        val target = File(root, "restored")

        assertEquals(emptyList<ArchiveProblem>(), archives.extract(archive, target, passphrase, derivation))

        val original = workspace.projectDir("p")
        val restored = target.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(target).path }.sorted().toList()
        assertTrue("nothing was restored", restored.isNotEmpty())
        restored.forEach { path ->
            assertArrayEqualsBytes(File(original, path), File(target, path))
        }
    }

    @Test fun aWrongPassphraseIsRefusedByTheRealKdf() {
        val archive = write("sealed.bcaz")

        val problems = archives.verify(archive, "not it".toCharArray(), derivation)

        assertEquals(listOf(ArchiveProblem.WrongPassphrase), problems)
    }

    @Test fun theManifestIsStillReadableWithoutThePassphrase() {
        val archive = write("sealed.bcaz")

        val manifest = requireNotNull(archives.peek(archive)) { "the manifest must be readable" }

        assertEquals("Encrypted Ledger", manifest.projectTitle)
        assertTrue(manifest.encryption is ArchiveEncryption.Encrypted)
        val encryption = manifest.encryption as ArchiveEncryption.Encrypted
        assertEquals("argon2id", encryption.kdf)
        assertEquals("AES-256-GCM", encryption.cipher)
        assertEquals(ArchiveEncryption.SALT_BYTES, encryption.salt.size)
        assertEquals(ArchiveEncryption.NONCE_BYTES, encryption.nonce.size)
    }

    @Test fun theShippedCostIsSlowEnoughToMatterAndFastEnoughToUse() {
        val cost = KdfCost()
        val salt = ByteArray(ArchiveEncryption.SALT_BYTES) { it.toByte() }

        val started = System.currentTimeMillis()
        val key = derivation.derive(passphrase, salt, cost)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(32, key.size)
        // Not a benchmark, a guard rail: a derivation that finishes instantly is not doing
        // its job, and one that takes ten seconds makes people turn encryption off.
        assertTrue("Argon2id finished suspiciously fast: ${elapsed}ms", elapsed > 50)
        assertTrue("Argon2id took ${elapsed}ms, which is too slow to live with", elapsed < 10_000)
        android.util.Log.i("Argon2BackupTest", "argon2id ${cost.memoryKib}KiB x${cost.iterations} took ${elapsed}ms")
    }

    @Test fun theSameInputsGiveTheSameKey() {
        val salt = ByteArray(ArchiveEncryption.SALT_BYTES) { 7 }

        val first = derivation.derive(passphrase, salt, KdfCost())
        val second = derivation.derive(passphrase, salt, KdfCost())

        assertTrue("Argon2id is not deterministic", first.contentEquals(second))
    }

    @Test fun aDifferentSaltGivesADifferentKey() {
        val one = derivation.derive(passphrase, ByteArray(16) { 1 }, KdfCost())
        val two = derivation.derive(passphrase, ByteArray(16) { 2 }, KdfCost())

        assertFalse("the salt is not reaching the KDF", one.contentEquals(two))
    }

    @Test fun theLedgerIsNotReadableInsideTheArchive() {
        val marker = "Assets:Bank:Checking"
        File(workspace.projectDir("p"), "files/transactions.beancount")
            .appendText("\n2026-08-24 * \"Rent\"\n  $marker  1.00 USD\n  Expenses:Rent\n")

        val bytes = write("no-plaintext.bcaz").readBytes()

        assertFalse(
            "an account name was readable in an encrypted archive",
            String(bytes, Charsets.ISO_8859_1).contains(marker),
        )
    }

    /**
     * The bug this test exists for.
     *
     * `backupTo` takes a `CharArray` and the caller wipes its own copy the moment the call
     * returns — which is right, and which broke encryption completely the first time it ran
     * on a device: the coroutine derived its key *after* the wipe, from a buffer of NULs, and
     * produced an archive that the correct passphrase could not open. The controller now
     * copies the passphrase synchronously, and this reproduces the caller's behaviour to
     * prove it.
     */
    @Test fun aPassphraseWipedByTheCallerImmediatelyStillEncryptsCorrectly(): Unit = runBlocking {
        val directory = File(context.cacheDir, "wipe-race").apply { deleteRecursively(); mkdirs() }
        val destination = File(directory, "sealed.bcaz")
        val controller = BackupController(
            workspace = workspace,
            registry = ProjectRegistry(workspace, File(directory, "state")),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            appVersion = "0.1.0",
        )

        val callerCopy = "the real passphrase".toCharArray()
        controller.backupTo(context.contentResolver, Uri.fromFile(destination), "p", callerCopy)
        // Exactly what MainActivity does: wipe as soon as the call returns.
        callerCopy.fill('\u0000')

        withTimeout(30_000) { controller.message.first { it?.startsWith("Backed up") == true } }

        assertEquals(
            "the archive was not encrypted with the passphrase the caller passed",
            emptyList<ArchiveProblem>(),
            archives.verify(destination, "the real passphrase".toCharArray(), derivation),
        )
        directory.deleteRecursively()
        Unit
    }

    private fun assertArrayEqualsBytes(expected: File, actual: File) {
        assertTrue(
            "${actual.name} differs after an encrypted round trip",
            expected.readBytes().contentEquals(actual.readBytes()),
        )
    }
}
