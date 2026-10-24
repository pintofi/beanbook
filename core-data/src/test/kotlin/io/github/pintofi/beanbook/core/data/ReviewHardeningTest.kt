package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.domain.TemplatePosting
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cases found while reading `core-data` file by file. Each one was a way the app could lose,
 * leak or be made to spend something it should not, and each states the case it is about.
 */
class ReviewHardeningTest {

    private val root: File = Files.createTempDirectory("review-hardening").toFile()
    private val workspace = ProjectWorkspace(root)

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    // --- documents ---------------------------------------------------------------------

    @Test
    fun `a ledger cannot list another project's documents by naming a folder that shares a prefix`() {
        workspace.createProject("proj", title = "A")
        workspace.createProject("proj2", title = "B")
        val other = workspace.projectDir("proj2").resolve("documents/secret.pdf")
        other.parentFile.mkdirs()
        other.writeText("not yours")

        val listed = DocumentStore(workspace).list("proj", extraRoots = listOf("../proj2/documents"))

        assertTrue(listed.none { it.contains("secret.pdf") }, "listed another project's file: $listed")
    }

    @Test
    fun `trashing a document does not remove an empty folder outside documents`() {
        workspace.createProject("p", title = "A")
        val store = DocumentStore(workspace)
        val receipts = workspace.projectDir("p").resolve("receipts/2026").apply { mkdirs() }
        receipts.resolve("a.pdf").writeText("x")

        assertNotNull(store.trash("p", "receipts/2026/a.pdf"))

        assertTrue(workspace.projectDir("p").resolve("receipts").isDirectory, "the person's own folder was removed")
    }

    @Test
    fun `a stored document is whole on disk`() {
        workspace.createProject("p", title = "A")
        val bytes = ByteArray(300_000) { (it % 251).toByte() }

        val stored = DocumentStore(workspace).store("p", "Expenses:Food", LocalDate.of(2026, 1, 2), bytes)

        assertTrue(workspace.projectDir("p").resolve(stored.relativePath).readBytes().contentEquals(bytes))
    }

    // --- backups -----------------------------------------------------------------------

    @Test
    fun `hashing an entry stops one byte past what the manifest says`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }

        val (read, _) = BackupArchive.hashAtMost(endless, limit = 1_001)

        assertEquals(1_001L, read)
        assertEquals(1_001L, served, "read past the limit")
    }

    @Test
    fun `a file called key in a project is backed up like any other`() {
        assertFalse(BackupArchive.isExcluded("key"))
        assertFalse(BackupArchive.isExcluded("documents/Assets/Bank/key"))
        assertFalse(BackupArchive.isExcluded("entitlement"))
        // What is runtime state still is.
        assertTrue(BackupArchive.isExcluded("files/.main.beancount.0123.picklecache"))
        assertTrue(BackupArchive.isExcluded("files/x.beancount.tmp"))
    }

    private fun encryptionJson(
        memoryKib: Int = 65_536,
        iterations: Int = 3,
        parallelism: Int = 2,
        kdf: String = ArchiveEncryption.ARGON2ID,
        cipher: String = ArchiveEncryption.CIPHER,
        nonceBytes: Int = ArchiveEncryption.NONCE_BYTES,
    ): Json = Json.obj(
        "cipher" to Json.of(cipher),
        "kdf" to Json.of(kdf),
        "salt" to Json.of(ArchiveEncryption.encode(ByteArray(16))),
        "nonce" to Json.of(ArchiveEncryption.encode(ByteArray(nonceBytes))),
        "iterations" to Json.of(iterations),
        "memory_kib" to Json.of(memoryKib),
        "parallelism" to Json.of(parallelism),
    )

    @Test
    fun `the parameters an archive states for its own key derivation are bounded`() {
        assertNotNull(ArchiveEncryption.fromJson(encryptionJson()), "the defaults must be accepted")

        // A manifest is not authenticated: these are requests from whoever made the archive.
        assertNull(ArchiveEncryption.fromJson(encryptionJson(memoryKib = Int.MAX_VALUE)), "gigabytes of memory")
        assertNull(ArchiveEncryption.fromJson(encryptionJson(iterations = 1_000_000)), "billions of passes")
        assertNull(ArchiveEncryption.fromJson(encryptionJson(parallelism = 4_096)))
        assertNull(ArchiveEncryption.fromJson(encryptionJson(iterations = 0)))
        assertNull(ArchiveEncryption.fromJson(encryptionJson(kdf = "pbkdf2")), "a derivation this does not read")
        assertNull(ArchiveEncryption.fromJson(encryptionJson(cipher = "ChaCha20")), "a cipher this does not read")
        assertNull(ArchiveEncryption.fromJson(encryptionJson(nonceBytes = 4)))
    }

    // --- imports -----------------------------------------------------------------------

    @Test
    fun `a link to a folder is a link, not a way to import what it points at`() {
        val outside = Files.createTempDirectory("outside").toFile()
            .apply { resolve("stolen.beancount").writeText("; no\n") }
        val project = Files.createTempDirectory("src").toFile()
        project.resolve("main.beancount").writeText("; ok\n")
        Files.createSymbolicLink(project.resolve("link").toPath(), outside.toPath())

        val entries = DirectoryImportSource(project).entries()

        assertTrue(entries.any { it.path == "link" && it.isSymlink }, "the link itself must be reported: $entries")
        assertTrue(entries.none { it.path.contains("stolen") }, "followed the link: $entries")
        outside.deleteRecursively()
        project.deleteRecursively()
    }

    @Test
    fun `an archive with far too many entries is refused without going through them`() {
        val src = Files.createTempDirectory("many").toFile()
        repeat(5) { src.resolve("f$it.beancount").writeText("; $it\n") }

        val plan = ProjectImporter(workspace, ImportLimits(maxEntries = 2)).plan(DirectoryImportSource(src))

        assertTrue(plan.files.isEmpty())
        assertIs<ImportProblem.TooManyEntries>(plan.problems.single())
        src.deleteRecursively()
    }

    @Test
    fun `a project whose root is not main-beancount but which has a main-beancount is refused clearly`() {
        val src = Files.createTempDirectory("clash").toFile()
        src.resolve("root.beancount").writeText("include \"main.beancount\"\n")
        src.resolve("main.beancount").writeText("; included, not the root\n")

        val result = ProjectImporter(workspace).import(DirectoryImportSource(src), "clash")

        val rejected = assertIs<ImportResult.Rejected>(result)
        assertIs<ImportProblem.MainFileTaken>(rejected.problems.single())
        assertFalse(workspace.projectDir("clash").exists(), "nothing half-imported is left")
        src.deleteRecursively()
    }

    // --- drafts, templates, widgets ----------------------------------------------------

    @Test
    fun `a draft cannot be written outside the drafts folder`() {
        workspace.createProject("p", title = "A")
        val drafts = DraftStore(workspace)

        assertFailsWith<IllegalArgumentException> {
            drafts.save("p", "../../../../escaped", "text", "sha")
        }
        assertFalse(root.resolve("escaped.draft").exists())
    }

    @Test
    fun `a template with a carriage return in its payee survives being saved`() {
        workspace.createProject("p", title = "A")
        val store = TemplateStore(workspace)
        val template = EntryTemplate(
            name = "Rent",
            currency = "EUR",
            postings = listOf(
                TemplatePosting("Expenses:Rent", java.math.BigDecimal("750.00")),
                TemplatePosting("Assets:Bank"),
            ),
            payee = "Land\rlord",
            narration = "line one\r\nline two",
        )

        store.save("p", template)

        assertEquals(listOf(template), store.load("p"))
    }

    @Test
    fun `two projects whose ids differ only by an underscore do not share a widget file`() {
        val cache = WidgetCache(root.resolve("state"))
        fun snapshot(id: String, balance: String) = WidgetSnapshot(
            projectId = id, projectTitle = id, generatedAtEpochMs = 1L,
            balances = mapOf("Assets:Cash" to balance), netWorth = null, periodIncome = null,
            periodExpense = null, clean = true,
        )
        cache.write(snapshot("a_b", "1.00 EUR"))
        cache.write(snapshot("ab", "2.00 EUR"))

        // Asking for an account only the older of the two newest snapshots has finds the right one.
        val files = root.resolve("state").listFiles { f -> f.name.startsWith("widgets-") }.orEmpty().map { it.name }
        assertTrue("widgets-a_b.json" in files && "widgets-ab.json" in files, "files: $files")
    }

    // --- the log -----------------------------------------------------------------------

    @Test
    fun `an authorization header is redacted with its scheme, and a cookie header whole`() {
        val bearer = DiagnosticLog.redact("sent Authorization: Bearer abc.def.ghi to the server")
        assertFalse(bearer.contains("abc.def.ghi"), bearer)

        val cookie = DiagnosticLog.redact("Cookie: session=aaa; other=bbb; theme=dark")
        assertFalse(cookie.contains("aaa") || cookie.contains("bbb"), cookie)

        val words = DiagnosticLog.redact("passphrase: correct horse battery staple")
        assertFalse(words.contains("horse") || words.contains("staple"), words)
    }

    @Test
    fun `entries can be written from many threads at once`() {
        val log = DiagnosticLog(root.resolve("log"))
        val pool = Executors.newFixedThreadPool(8)
        val tasks = (1..8).map { thread ->
            Callable { repeat(400) { n -> log.append(LogLevel.INFO, "T$thread", "message $n") } }
        }
        pool.invokeAll(tasks).forEach { it.get() }
        pool.shutdown()

        val lines = log.text().lines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 100, "lines were lost")
        val shape = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} I/T\d: message \d+$""")
        assertNull(lines.firstOrNull { !shape.matches(it) }, "a timestamp was corrupted")
    }
}
