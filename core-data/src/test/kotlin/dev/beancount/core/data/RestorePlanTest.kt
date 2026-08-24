package dev.beancount.core.data

import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RestorePlanTest {

    private val root: File = Files.createTempDirectory("restore").toFile()
    private val workspace = ProjectWorkspace(root)
    private val archives = BackupArchive(workspace)
    private val plan = RestorePlan(workspace)
    private val now = Instant.parse("2026-08-24T10:00:00Z")

    init {
        workspace.createProject("p", title = "Live")
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun backup(name: String = "backup.bcaz"): File {
        val file = File(root, name)
        file.outputStream().use { archives.write("p", it, appVersion = "0.1.0", now = now) }
        return file
    }

    private fun projectFile(path: String) = File(workspace.projectDir("p"), path)

    @Test
    fun `staging a good archive touches nothing live and reports no changes`() {
        val archive = backup()
        val before = projectFile("main.beancount").readText()

        val staged = assertIs<RestoreStaged.Ready>(plan.stage("p", archive))

        assertTrue(staged.diff.isEmpty, staged.diff.describe())
        assertTrue(
            staged.diff.describe().contains("would change nothing"),
            staged.diff.describe(),
        )
        assertEquals(before, projectFile("main.beancount").readText())
    }

    @Test
    fun `the diff names what would be added, replaced and lost`() {
        val archive = backup()
        // The project moves on after the backup was taken.
        projectFile("main.beancount").appendText("\n2026-08-25 note Assets:Cash \"later\"\n")
        projectFile("files/new-since-backup.beancount").writeText("; typed after the backup\n")

        val staged = assertIs<RestoreStaged.Ready>(plan.stage("p", archive))

        assertEquals(listOf("main.beancount"), staged.diff.changed)
        assertEquals(listOf("files/new-since-backup.beancount"), staged.diff.removed)
        assertEquals(emptyList(), staged.diff.added)
        val described = staged.diff.describe()
        assertTrue(described.contains("1 file(s) would be replaced"), described)
        assertTrue(
            described.contains("not in this backup"),
            "the diff must say what would be lost: $described",
        )
    }

    @Test
    fun `a truncated archive is refused and nothing is staged`() {
        val archive = backup()
        // What an interrupted copy or a full disk actually leaves behind. (A single flipped
        // byte is not reliably damage: the first version of this test flipped one and the
        // archive still verified, because it landed in a field nothing reads.)
        val damaged = File(root, "damaged.bcaz").apply {
            writeBytes(archive.readBytes().copyOf(archive.readBytes().size / 2))
        }

        val staged = plan.stage("p", damaged)

        assertIs<RestoreStaged.Refused>(staged)
        assertFalse(plan.stagingDir("p").exists(), "a refused restore must leave nothing behind")
    }

    @Test
    fun `activating keeps the previous project as a rollback point`() {
        val archive = backup()
        projectFile("main.beancount").appendText("\n; changed after the backup\n")
        val staged = assertIs<RestoreStaged.Ready>(plan.stage("p", archive))

        val kept = plan.activate("p", staged.staging, now)

        assertFalse(
            projectFile("main.beancount").readText().contains("changed after the backup"),
            "the restore did not take effect",
        )
        assertTrue(kept.isDirectory, "the previous project must be kept")
        assertTrue(
            File(kept, "main.beancount").readText().contains("changed after the backup"),
            "the rollback point must hold what was there before",
        )
        assertEquals(listOf(kept.name), plan.rollbackPoints("p").map { it.name })
    }

    @Test
    fun `a restored project is a project again, not a staging directory`() {
        val archive = backup()
        val staged = assertIs<RestoreStaged.Ready>(plan.stage("p", archive))

        plan.activate("p", staged.staging, now)

        assertTrue(workspace.exists("p"), "the workspace must see the restored project")
        assertFalse(plan.stagingDir("p").exists(), "staging must not survive activation")
    }

    @Test
    fun `discarding a staged restore leaves the project alone`() {
        val archive = backup()
        val before = projectFile("main.beancount").readText()
        plan.stage("p", archive)

        plan.discardStaging("p")

        assertFalse(plan.stagingDir("p").exists())
        assertEquals(before, projectFile("main.beancount").readText())
    }

    @Test
    fun `runtime state is not counted as a difference`() {
        val archive = backup()
        // These are excluded from backups, so their absence from an archive is not a change.
        File(workspace.projectDir("p"), ".cache/parse.cache").apply { parentFile.mkdirs() }
            .writeText("cache")

        val staged = assertIs<RestoreStaged.Ready>(plan.stage("p", archive))

        assertTrue(staged.diff.isEmpty, staged.diff.describe())
    }
}

class BackupRetentionTest {

    private val now = Instant.parse("2026-08-24T10:00:00Z")

    private fun archive(name: String, daysAgo: Long) =
        BackupRetention.Archive(name, now.minus(Duration.ofDays(daysAgo)))

    @Test
    fun `the newest are kept whatever their age`() {
        val retention = BackupRetention(keepCount = 3, keepFor = Duration.ofDays(7))
        val archives = (1..5).map { archive("backup-$it", daysAgo = it * 100L) }

        val kept = retention.keep(archives, now).map { it.name }

        assertEquals(listOf("backup-1", "backup-2", "backup-3"), kept.sorted())
    }

    @Test
    fun `older archives beyond the count are dropped only once they age out`() {
        val retention = BackupRetention(keepCount = 2, keepFor = Duration.ofDays(30))
        val archives = listOf(
            archive("today", 0),
            archive("yesterday", 1),
            archive("last-week", 7),
            archive("last-year", 365),
        )

        val expired = retention.expired(archives, now).map { it.name }

        assertEquals(listOf("last-year"), expired)
    }

    @Test
    fun `a retention setting can never delete every backup`() {
        val retention = BackupRetention(keepCount = 0, keepFor = Duration.ZERO)
        val archives = listOf(archive("only-one", 10_000))

        assertEquals(emptyList(), retention.expired(archives, now))
        assertEquals(listOf("only-one"), retention.keep(archives, now).map { it.name })
    }

    @Test
    fun `nothing to retain is not an error`() {
        assertEquals(emptyList(), BackupRetention().expired(emptyList(), now))
    }
}
