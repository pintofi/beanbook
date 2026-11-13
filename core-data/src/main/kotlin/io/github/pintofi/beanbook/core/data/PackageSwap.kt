package io.github.pintofi.beanbook.core.data

import java.io.File

/**
 * Replacing the folder of fetched packages in one step, and putting the old one back.
 *
 * An update is built in a copy ([stageCopy]), and the copy takes the place of the folder with two
 * renames that are journalled: the folder becomes `-previous` and the copy becomes the folder. Until
 * the new packages have been proved to import ([proven]) a note says so, and a process that is killed
 * with that note present puts the earlier folder back by itself ([recover]), so a half-trusted update
 * is never what the engine imports after a restart. One earlier folder is kept.
 *
 * All the folders are beside [root], on one filesystem, so each step is a rename.
 */
class PackageSwap(private val root: File) {

    private fun sibling(suffix: String) = File(root.parentFile ?: root, "${root.name}-$suffix")

    val previous: File get() = sibling("previous")
    val next: File get() = sibling("next")
    private val failed get() = sibling("failed")
    private val swapNote get() = sibling("swap")
    private val trialNote get() = sibling("trial")

    fun hasPrevious(): Boolean = previous.isDirectory && !swapNote.exists() && !trialNote.exists()

    /** A copy of the packages to build the update in; the folder itself is not touched. */
    fun stageCopy(): File {
        next.deleteRecursively()
        if (root.isDirectory) root.copyRecursively(next, overwrite = true) else next.mkdirs()
        return next
    }

    /**
     * Makes the staged copy the folder, keeping what was there as the earlier one, and leaves a note
     * that the new packages are not yet proved.
     */
    fun commit(): Result<Unit> = runCatching {
        check(next.isDirectory) { "there is nothing staged to switch to" }
        trialNote.writeText("1")
        swapNote.writeText("1")
        previous.deleteRecursively()
        if (root.exists()) check(root.renameTo(previous)) { "could not set the installed packages aside" }
        if (!next.renameTo(root)) {
            previous.renameTo(root)
            swapNote.delete()
            trialNote.delete()
            error("could not move the new packages into place, so they are as they were")
        }
        swapNote.delete()
    }

    /** The new packages import: keep them, and the earlier ones to go back to. */
    fun proven() {
        trialNote.delete()
    }

    /** Whether a switch is still waiting to be proved. */
    fun isOnTrial(): Boolean = trialNote.exists()

    /** Brings the earlier packages back and drops the ones that replaced them. */
    fun rollback(): Result<Unit> = runCatching {
        check(previous.isDirectory) { "there are no earlier packages to go back to" }
        failed.deleteRecursively()
        if (root.exists()) check(root.renameTo(failed)) { "could not set the current packages aside" }
        if (!previous.renameTo(root)) {
            failed.renameTo(root)
            error("could not bring the earlier packages back, so the current ones are as they were")
        }
        failed.deleteRecursively()
        trialNote.delete()
    }

    /** Drops the earlier packages to free the space. */
    fun discardPrevious() {
        if (!swapNote.exists() && !trialNote.exists()) previous.deleteRecursively()
    }

    /**
     * Finishes or undoes what a killed process left. Run before anything is handed to the engine.
     * Returns what it did, for the log, or null when there was nothing to do.
     */
    fun recover(): String? {
        var did: String? = null
        if (swapNote.exists()) {
            when {
                !root.exists() && previous.isDirectory -> {
                    previous.renameTo(root)
                    next.deleteRecursively()
                    trialNote.delete()
                    did = "put the earlier packages back after an interrupted switch"
                }
                !root.exists() && next.isDirectory -> {
                    next.renameTo(root)
                    did = "finished an interrupted switch"
                }
            }
            swapNote.delete()
        }
        if (trialNote.exists()) {
            if (previous.isDirectory) {
                rollback()
                did = "put the earlier packages back, because the update was never proved"
            }
            trialNote.delete()
        }
        next.deleteRecursively()
        failed.deleteRecursively()
        return did
    }
}
