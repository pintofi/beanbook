package io.github.pintofi.beanbook.app.engine

import android.os.FileObserver
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Notices when a `.beancount` file in the open project changes on disk.
 *
 * The app used to have no watcher at all: an edit made by a script, a mirror pull, a restore,
 * or a desktop writing into a synced folder left every screen showing the ledger as it was
 * until the user pulled to refresh. Fava, running in the engine process, watches the same
 * files itself, so the two views of one ledger disagreed.
 *
 * `FileObserver` rather than polling or `watchfiles`: it costs nothing while nothing changes,
 * and the alternative -- a timer reading modification times -- wakes the device for an answer
 * that is almost always "no". It does not descend into subfolders, so the folders that hold
 * ledger files are collected and watched together, which the API 29 list constructor allows.
 *
 * Changes arrive as a burst -- an editor writes a temporary file, renames it, and touches the
 * folder -- so they are coalesced: one reload after [QUIET_MS] of silence, not one per event.
 * That also covers the app's own writes, which reload the ledger themselves.
 */
class LedgerWatcher(
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {

    private var observer: FileObserver? = null
    private var pending: Job? = null

    // The app's own writes are not outside edits. `ProjectWriter` re-parses the ledger
    // as part of every write and the app reloads it afterwards; the watcher reloading a third
    // time put two more full parses in the engine's queue for each save, and a save with a
    // document -- two writes -- waited behind four.
    @Volatile private var writing = false
    @Volatile private var quietUntil = 0L

    /** Told by the app's writer when one of its own writes starts and ends. */
    fun ownWrite(active: Boolean) {
        writing = active
        // The rename that ends a write is reported a moment after it happens, so the quiet
        // lasts a little past the end of the write as well.
        if (!active) quietUntil = android.os.SystemClock.uptimeMillis() + OWN_WRITE_GRACE_MS
        pending?.cancel()
    }

    /** Watches every folder of [projectDir] holding ledger files, replacing the last watch. */
    fun watch(projectDir: File) {
        stop()
        if (!projectDir.isDirectory) return
        val folders = ledgerFolders(projectDir)
        if (folders.isEmpty()) return
        observer = object : FileObserver(folders, EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                // Only the events asked for. Removing a watch makes the kernel send
                // IN_IGNORED with no path, and `watch` replaces the observer on every reload;
                // the new observer can be handed the old one's IN_IGNORED (watch descriptors
                // are reused), which read as "something changed", scheduled a reload, which
                // re-watched, which sent another -- a reload every 0.7 s, for ever, from the
                // first outside change on (seen on the emulator after an Undo).
                if (event and EVENTS == 0) return
                // A rename reports the old name and then the new one, and a delete reports
                // nothing at all, so a null path counts: the safe reading of "something
                // happened here" is to read the ledger again.
                if (path != null && !path.endsWith(".beancount")) return
                schedule()
            }
        }
        runCatching { observer?.startWatching() }
            .onFailure { Diagnostics.w(TAG, "could not watch ${projectDir.name}", it) }
    }

    /** Stops watching. Called when the app goes to the background or the project changes. */
    fun stop() {
        runCatching { observer?.stopWatching() }
        observer = null
        pending?.cancel()
        pending = null
    }

    /**
     * The project folder and every folder under it that holds a `.beancount` file.
     *
     * Documents are skipped: a folder of receipts can hold thousands of files, none of which
     * changes what the ledger says, and watching it would reload on every scan the app saves.
     */
    private fun ledgerFolders(projectDir: File): List<File> {
        val found = mutableListOf(projectDir)
        projectDir.walkTopDown()
            .maxDepth(WALK_DEPTH)
            .onEnter { it.name != io.github.pintofi.beanbook.core.domain.DocumentName.ROOT }
            .filter { it.isDirectory && it != projectDir }
            .filter { dir -> dir.listFiles()?.any { it.name.endsWith(".beancount") } == true }
            .forEach { found += it }
        return found
    }

    private fun schedule() {
        if (writing || android.os.SystemClock.uptimeMillis() < quietUntil) return
        pending?.cancel()
        pending = scope.launch {
            delay(QUIET_MS)
            Diagnostics.i(TAG, "ledger changed on disk; reloading")
            onChanged()
        }
    }

    private companion object {
        const val TAG = "LedgerWatcher"

        /**
         * How long the folder must be quiet before the ledger is read again.
         *
         * Long enough that a multi-file write -- a restore, a mirror pull, an import writing
         * several includes -- is one reload rather than one per file, and short enough that a
         * person who saved a file on their desktop sees it by the time they look at the phone.
         */
        const val QUIET_MS = 700L

        /** How long after one of the app's own writes its file events are still its own. */
        const val OWN_WRITE_GRACE_MS = 1500L

        /** Far past any real project, and a bound on walking a folder somebody has filled. */
        const val WALK_DEPTH = 4

        const val EVENTS = FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or
            FileObserver.DELETE or
            FileObserver.CREATE
    }
}
