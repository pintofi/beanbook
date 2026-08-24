package dev.beancount.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every combination of "changed here", "changed there" and "last known synced".
 *
 * This is where a sync loses data, so the cases are enumerated rather than reasoned about:
 * eight of them are decidable, and the rest wait for a person.
 */
class MirrorPlanTest {

    private val path = "files/transactions.beancount"

    private fun plan(
        here: String? = null,
        there: String? = null,
        synced: String? = null,
    ): List<MirrorAction> = MirrorPlan.plan(
        local = here?.let { mapOf(path to it) }.orEmpty(),
        external = there?.let { mapOf(path to it) }.orEmpty(),
        lastSynced = synced?.let { mapOf(path to it) }.orEmpty(),
    ).actions

    @Test
    fun `identical on both sides is nothing to do`() {
        assertEquals(emptyList(), plan(here = "a", there = "a", synced = "a"))
    }

    @Test
    fun `identical on both sides needs no action even with no record of a sync`() {
        // The state file is a cache and can be lost; two identical files are still in sync.
        assertEquals(emptyList(), plan(here = "a", there = "a", synced = null))
    }

    @Test
    fun `changed here only is pushed out`() {
        assertEquals(listOf(MirrorAction.PushOut(path)), plan(here = "b", there = "a", synced = "a"))
    }

    @Test
    fun `changed there only is pulled in`() {
        assertEquals(listOf(MirrorAction.PullIn(path)), plan(here = "a", there = "b", synced = "a"))
    }

    @Test
    fun `changed in both places is a conflict, and nothing is overwritten`() {
        val action = plan(here = "b", there = "c", synced = "a").single()

        assertEquals(
            MirrorAction.Conflict(path, MirrorAction.Conflict.Kind.BOTH_CHANGED),
            action,
        )
        assertTrue(
            (action as MirrorAction.Conflict).describe().contains("Both versions are kept"),
            action.describe(),
        )
    }

    @Test
    fun `a file new here is pushed out`() {
        assertEquals(listOf(MirrorAction.PushOut(path)), plan(here = "a"))
    }

    @Test
    fun `a file new there is pulled in`() {
        assertEquals(listOf(MirrorAction.PullIn(path)), plan(there = "a"))
    }

    @Test
    fun `deleted here and untouched there is deleted there`() {
        assertEquals(
            listOf(MirrorAction.DeleteThere(path)),
            plan(here = null, there = "a", synced = "a"),
        )
    }

    @Test
    fun `deleted here but changed there is a conflict, not a deletion`() {
        val action = plan(here = null, there = "b", synced = "a").single()

        assertEquals(
            MirrorAction.Conflict(path, MirrorAction.Conflict.Kind.DELETED_HERE_CHANGED_THERE),
            action,
        )
        assertTrue(
            (action as MirrorAction.Conflict).describe().contains("Nothing has been removed"),
            action.describe(),
        )
    }

    @Test
    fun `changed here but gone there is a conflict, not a silent re-upload`() {
        assertEquals(
            MirrorAction.Conflict(path, MirrorAction.Conflict.Kind.CHANGED_HERE_DELETED_THERE),
            plan(here = "b", there = null, synced = "a").single(),
        )
    }

    @Test
    fun `gone there and unchanged here waits for a decision rather than deleting the ledger`() {
        // The tempting inference — "they deleted it, so delete it here" — is how a sync
        // provider hiccup turns into lost books.
        val action = plan(here = "a", there = null, synced = "a").single()

        assertEquals(
            MirrorAction.Conflict(path, MirrorAction.Conflict.Kind.DELETED_THERE),
            action,
        )
        assertTrue(
            (action as MirrorAction.Conflict).describe().contains("Nothing has been deleted here"),
            action.describe(),
        )
    }

    @Test
    fun `gone from both sides leaves only a stale record, and no action`() {
        assertEquals(emptyList(), plan(here = null, there = null, synced = "a"))
    }

    @Test
    fun `a plan separates what it may do from what it must ask about`() {
        val result = MirrorPlan.plan(
            local = mapOf("a" to "1", "b" to "2", "c" to "3"),
            external = mapOf("a" to "0", "b" to "2", "c" to "9"),
            lastSynced = mapOf("a" to "0", "b" to "2", "c" to "0"),
        )

        assertEquals(listOf("a"), result.pushes.map { it.path })
        assertEquals(listOf("c"), result.conflicts.map { it.path })
        assertEquals(listOf("a"), result.automatic.map { it.path })
        assertTrue(!result.isClean)
    }

    @Test
    fun `a whole project in sync produces no actions at all`() {
        val files = (1..20).associate { "files/file-$it.beancount" to "hash-$it" }

        assertTrue(MirrorPlan.plan(files, files, files).isClean)
    }
}

class MirrorStateTest {

    private val root: File = Files.createTempDirectory("mirror").toFile()
    private val workspace = ProjectWorkspace(root)
    private val state = MirrorState(workspace)

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    @Test
    fun `state survives a restart`() {
        val hashes = mapOf("main.beancount" to "abc", "files/a b c.beancount" to "def")

        state.write("p", hashes)

        assertEquals(hashes, MirrorState(workspace).read("p"))
    }

    @Test
    fun `state lives in the cache, never in the project tree`() {
        state.write("p", mapOf("main.beancount" to "abc"))

        val inProject = workspace.projectDir("p").walkTopDown().filter { it.isFile }.toList()
        assertTrue(inProject.isEmpty(), "mirror state leaked into the project: $inProject")
        assertTrue(workspace.cacheDir.resolve("p/mirror-state").isFile)
    }

    @Test
    fun `losing the state file makes the next pass conservative, not destructive`() {
        state.write("p", mapOf("main.beancount" to "abc"))
        state.clear("p")

        // With no record, a file present on both sides is left alone and a file present on
        // one side is copied — never deleted.
        val result = MirrorPlan.plan(
            local = mapOf("main.beancount" to "abc"),
            external = mapOf("other.beancount" to "def"),
            lastSynced = state.read("p"),
        )

        assertEquals(
            listOf(MirrorAction.PushOut("main.beancount"), MirrorAction.PullIn("other.beancount")),
            result.actions,
        )
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.deletions.isEmpty(), "a lost state file must never delete anything")
    }

    @Test
    fun `an empty state reads as empty rather than failing`() {
        assertEquals(emptyMap(), state.read("never-mirrored"))
    }
}
