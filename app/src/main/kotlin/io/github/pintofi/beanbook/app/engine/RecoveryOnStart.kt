package io.github.pintofi.beanbook.app.engine

import android.util.Log
import io.github.pintofi.beanbook.core.data.ProjectWriter

/**
 * Recovery runs before a project is shown, not after (docs/architecture.md §5).
 *
 * Process death mid-write is assumed, not exceptional: an interrupted write is decided by
 * re-parsing — kept if the result parses, rolled back to the snapshot if it does not —
 * and the outcome is reported to the user rather than fixed silently (invariant 3).
 */
class RecoveryOnStart(private val writer: ProjectWriter) {

    data class Report(val rolledForward: Int, val rolledBack: Int) {
        val recovered: Int get() = rolledForward + rolledBack
        val isClean: Boolean get() = recovered == 0

        /** Wording the user sees; deliberately plain about what happened to their data. */
        fun describe(): String = when {
            isClean -> "No interrupted saves."
            rolledBack == 0 -> "A save was interrupted last time. It has been finished, so your change is kept."
            rolledForward == 0 -> "A save was interrupted last time. It could not be finished, so the file was put " +
                "back as it was before."
            else -> "$recovered saves were interrupted last time: $rolledForward finished and kept, " +
                "$rolledBack put back as they were before."
        }
    }

    suspend fun run(projectId: String): Report {
        val outcomes = writer.recover(projectId)
        val report = Report(
            rolledForward = outcomes.count { it.rolledForward },
            rolledBack = outcomes.count { !it.rolledForward },
        )
        if (!report.isClean) {
            Log.w(TAG, "recovery: ${report.describe()}")
            outcomes.forEach {
                Log.w(TAG, "  txn=${it.txnId} op=${it.operation} rolledForward=${it.rolledForward}")
            }
        } else {
            Log.w(TAG, "recovery: clean start, no pending writes")
        }
        return report
    }

    private companion object {
        const val TAG = "Recovery"
    }
}
