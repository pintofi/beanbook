package io.github.pintofi.beanbook.core.data

import java.io.File

/**
 * What to do about one file that exists here, there, or both
 * (docs/storage-and-backup.md §6).
 *
 * The rule the whole design turns on: **never silently pick a winner.** Anything the app can
 * work out for itself, it does; anything that needs a human decision waits for one, with
 * both versions kept until it is made.
 */
sealed interface MirrorAction {
    val path: String

    /** Changed here since the last sync, untouched there: copy it out. */
    data class PushOut(override val path: String) : MirrorAction

    /** Changed there since the last sync, untouched here: offer to bring it in. */
    data class PullIn(override val path: String) : MirrorAction

    /** Deleted here, untouched there: remove it there too. */
    data class DeleteThere(override val path: String) : MirrorAction

    /**
     * Changed in both places, or deleted in one and changed in the other.
     *
     * Nothing is overwritten. Both versions are kept — the external one alongside as
     * `<file>.conflict-<timestamp>` — and the user chooses per file.
     */
    data class Conflict(
        override val path: String,
        val kind: Kind,
    ) : MirrorAction {
        enum class Kind {
            BOTH_CHANGED,

            /** Deleted here, changed there: deleting their edit is not ours to decide. */
            DELETED_HERE_CHANGED_THERE,

            /** Deleted there, changed here: re-creating or accepting the delete is a choice. */
            CHANGED_HERE_DELETED_THERE,

            /**
             * Gone there, unchanged here since the last sync. Deleting a ledger file because
             * a sync provider dropped it — or because a folder was moved — is exactly the
             * kind of "obvious" inference that loses someone their books.
             */
            DELETED_THERE,
        }

        fun describe(): String = when (kind) {
            Kind.BOTH_CHANGED ->
                "\"$path\" changed both on this device and in your folder. Both versions are " +
                    "kept; choose which one to keep."
            Kind.DELETED_HERE_CHANGED_THERE ->
                "\"$path\" was deleted on this device but changed in your folder. Nothing has " +
                    "been removed there."
            Kind.CHANGED_HERE_DELETED_THERE ->
                "\"$path\" changed on this device but is gone from your folder."
            Kind.DELETED_THERE ->
                "\"$path\" is gone from your folder but is unchanged on this device. Nothing " +
                    "has been deleted here."
        }
    }
}

/** Which side of a conflict the user chose to keep. */
enum class MirrorChoice { KEEP_MINE, KEEP_THEIRS }

/** What to do for one conflict, decided from the files as they are *now*. */
sealed interface MirrorResolution {
    /** Copy this device's file out to the folder. */
    data object PushOut : MirrorResolution

    /** Bring the folder's file in. */
    data object PullIn : MirrorResolution

    /** The folder deleted it and that choice is accepted: remove the file here. */
    data object DeleteHere : MirrorResolution

    /** This device deleted it and that choice is kept: remove the file from the folder. */
    data object DeleteThere : MirrorResolution

    /** The two sides already agree: only the record needs to be settled. */
    data object AlreadyAgreed : MirrorResolution

    /** The files changed again since the conflict was shown, so the choice no longer means what it did. */
    data object Stale : MirrorResolution
}

/** Everything the next mirror pass would do. */
data class MirrorPlanResult(
    val actions: List<MirrorAction>,
) {
    val pushes: List<MirrorAction.PushOut> get() = actions.filterIsInstance<MirrorAction.PushOut>()
    val pulls: List<MirrorAction.PullIn> get() = actions.filterIsInstance<MirrorAction.PullIn>()
    val deletions: List<MirrorAction.DeleteThere>
        get() = actions.filterIsInstance<MirrorAction.DeleteThere>()
    val conflicts: List<MirrorAction.Conflict>
        get() = actions.filterIsInstance<MirrorAction.Conflict>()

    val isClean: Boolean get() = actions.isEmpty()

    /** Actions the app may apply on its own: everything that is not a conflict. */
    val automatic: List<MirrorAction> get() = actions.filterNot { it is MirrorAction.Conflict }
}

/**
 * Decides what a mirror pass should do, from three sets of hashes.
 *
 * Pure, and separated from SAF entirely, because this is where a sync loses data: every
 * combination of "changed here", "changed there" and "last known synced" is a case with a
 * right answer, and they are all enumerated in the tests rather than reasoned about at the
 * call site.
 *
 * Content hashes, not timestamps: `DocumentFile` last-modified is unreliable across
 * providers (docs/storage-and-backup.md §6), and a file whose contents did not change is not
 * a change however its clock reads.
 */
object MirrorPlan {

    /**
     * What a conflict's [choice] does, judged on the files as they are now.
     *
     * The conflict is worked out again from the current hashes. If it is no longer the same
     * conflict the user was shown ([seen]), the answer is [MirrorResolution.Stale]: a card left on
     * screen must not delete a file that has changed since.
     */
    fun resolve(
        here: String?,
        there: String?,
        synced: String?,
        seen: MirrorAction.Conflict.Kind,
        choice: MirrorChoice,
    ): MirrorResolution {
        if (here == null && there == null) return MirrorResolution.AlreadyAgreed
        if (here != null && here == there) return MirrorResolution.AlreadyAgreed
        val now = plan(
            local = listOfNotNull(here?.let { "f" to it }).toMap(),
            external = listOfNotNull(there?.let { "f" to it }).toMap(),
            lastSynced = listOfNotNull(synced?.let { "f" to it }).toMap(),
        ).actions.singleOrNull()
        if (now !is MirrorAction.Conflict || now.kind != seen) return MirrorResolution.Stale
        return when (choice) {
            MirrorChoice.KEEP_MINE -> if (here != null) MirrorResolution.PushOut else MirrorResolution.DeleteThere
            MirrorChoice.KEEP_THEIRS -> if (there != null) MirrorResolution.PullIn else MirrorResolution.DeleteHere
        }
    }

    /**
     * The sync record after a pass: what matched is recorded, and a record for a file that is gone
     * from both sides is dropped. A record left behind made a file of the same content that
     * arrived in the folder later look like one this device had deleted, and it was removed.
     */
    fun agreed(
        local: Map<String, String>,
        external: Map<String, String>,
        lastSynced: Map<String, String>,
    ): Map<String, String> {
        val kept = lastSynced.filterKeys { it in local || it in external }.toMutableMap()
        local.forEach { (path, hash) -> if (external[path] == hash) kept[path] = hash }
        return kept
    }

    fun plan(
        local: Map<String, String>,
        external: Map<String, String>,
        lastSynced: Map<String, String>,
    ): MirrorPlanResult {
        val paths = (local.keys + external.keys + lastSynced.keys).sorted()

        val actions = paths.mapNotNull { path ->
            val here = local[path]
            val there = external[path]
            val synced = lastSynced[path]

            when {
                // Identical on both sides: nothing to do, whatever the state says.
                here != null && here == there -> null

                here != null && there != null -> {
                    val changedHere = here != synced
                    val changedThere = there != synced
                    when {
                        changedHere && changedThere ->
                            MirrorAction.Conflict(path, MirrorAction.Conflict.Kind.BOTH_CHANGED)
                        changedHere -> MirrorAction.PushOut(path)
                        changedThere -> MirrorAction.PullIn(path)
                        // Neither matches the recorded sync but both match each other: the
                        // earlier branch caught that, so this is unreachable in practice.
                        else -> null
                    }
                }

                // Only here: new locally, or deleted there.
                here != null && there == null ->
                    when {
                        synced == null -> MirrorAction.PushOut(path)
                        here != synced ->
                            MirrorAction.Conflict(
                                path,
                                MirrorAction.Conflict.Kind.CHANGED_HERE_DELETED_THERE,
                            )
                        else ->
                            MirrorAction.Conflict(path, MirrorAction.Conflict.Kind.DELETED_THERE)
                    }

                // Only there: new externally, or deleted here.
                here == null && there != null ->
                    when {
                        synced == null -> MirrorAction.PullIn(path)
                        there != synced ->
                            MirrorAction.Conflict(
                                path,
                                MirrorAction.Conflict.Kind.DELETED_HERE_CHANGED_THERE,
                            )
                        else -> MirrorAction.DeleteThere(path)
                    }

                // Gone from both sides; only the record remains, and it is stale.
                else -> null
            }
        }

        return MirrorPlanResult(actions)
    }
}

/**
 * What was last known to be identical on both sides, per file.
 *
 * Lives in the **cache** directory: it describes this device's view of a folder that may not
 * exist anywhere else, it is not part of the ledger, and losing it is harmless — a missing
 * record simply makes the next pass treat everything as new, which is conservative in the
 * right direction (nothing is deleted without a record saying it was synced).
 */
class MirrorState(private val workspace: ProjectWorkspace) {

    private fun stateFile(projectId: String): File =
        workspace.cacheDir.resolve(projectId).resolve("mirror-state")

    fun read(projectId: String): Map<String, String> {
        val file = stateFile(projectId)
        if (!file.isFile) return emptyMap()
        return file.readLines()
            .mapNotNull { line ->
                val separator = line.indexOf(' ')
                if (separator <= 0) return@mapNotNull null
                line.substring(separator + 1) to line.substring(0, separator)
            }
            .toMap()
    }

    fun write(projectId: String, hashes: Map<String, String>) {
        val file = stateFile(projectId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(hashes.entries.sortedBy { it.key }.joinToString("\n") { "${it.value} ${it.key}" })
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun clear(projectId: String) {
        stateFile(projectId).delete()
    }
}
