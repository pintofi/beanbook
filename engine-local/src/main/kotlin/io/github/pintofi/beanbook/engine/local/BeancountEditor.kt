package io.github.pintofi.beanbook.engine.local

import android.content.Context
import io.github.pintofi.beanbook.core.data.LedgerUnavailable
import io.github.pintofi.beanbook.core.data.LedgerValidator
import io.github.pintofi.beanbook.core.domain.EntrySlices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The real [LedgerValidator]: it re-parses the project with Beancount itself.
 *
 * A ledger that loads *with errors* is still a success here — errors are a normal state
 * and the writer surfaces them as warnings. Only a ledger that cannot be loaded at all is
 * a failure, and that is what triggers rollback.
 */
class BeancountValidator(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connection: EngineConnection = EngineConnection(context),
) : LedgerValidator {

    override suspend fun validate(mainFile: String): Result<List<String>> =
        withContext(io) {
            connection.withEngine { it.validate(mainFile) }
                // The engine being unreachable says nothing about the ledger. Typed so the
                // writer can tell it apart from Beancount refusing the file, and report the
                // difference to the user.
                .recoverCatching { failure -> throw LedgerUnavailable(failure.message ?: "engine unavailable") }
                .mapCatching { json ->
                if (json.startsWith("error: ")) error(json.removePrefix("error: "))
                val root = JSONObject(json)
                val errors = root.optJSONArray("errors")
                val messages = (0 until (errors?.length() ?: 0)).map { index ->
                    val error = errors!!.optJSONObject(index)
                    if (error == null) {
                        errors.optString(index)
                    } else {
                        val file = error.optString("file").substringAfterLast('/')
                        val line = error.opt("line")
                        val position = if (file.isNotEmpty() && line != null) "$file:$line " else ""
                        position + error.optString("message")
                    }
                }
                if (!root.optBoolean("ok")) {
                    throw IllegalStateException(
                        messages.firstOrNull() ?: "the ledger could not be parsed"
                    )
                }
                messages
            }
        }
}

/** Where an entry lives in its source file, as Beancount and Fava report it. */
data class EntrySlice(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val sha256: String,
)

/**
 * Locates entries in source and computes edited file content — without writing anything.
 * Writing belongs to `ProjectWriter`, which owns the journal and the atomic rename.
 */
class BeancountEditor(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connection: EngineConnection = EngineConnection(context),
) {

    /**
     * The entry that starts at [line] of [file], read from the file itself. [mainFile] is kept for
     * callers that pass it; nothing needs the ledger loaded to cut an entry out of a text file.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sliceAt(mainFile: String, file: String, line: Int): EntrySlice? =
        withContext(io) {
            val source = java.io.File(file)
            if (!source.isFile) return@withContext null
            val slice = EntrySlices.at(EntrySlices.linesOf(source.readText()), line)
                ?: return@withContext null
            EntrySlice(
                file = source.path,
                startLine = slice.startLine,
                endLine = slice.endLine,
                text = slice.text,
                sha256 = slice.sha256,
            )
        }

    suspend fun contentWithReplacement(file: String, from: Int, to: Int, newText: String): String =
        transform("replace", file, from, to, newText)

    suspend fun contentWithDeletion(file: String, from: Int, to: Int): String =
        transform("delete", file, from, to, null)

    /**
     * The edited content comes back through a file in the app's cache, not through Binder:
     * a 7.7 MB ledger would exceed the ~1 MB transaction limit
     *.
     */
    private suspend fun transform(
        op: String,
        file: String,
        from: Int,
        to: Int,
        newText: String?,
    ): String = withContext(io) {
        val payload = connection.payloadFile(op)
        val result = connection
            .withEngine { it.transformToFile(op, file, from, to, newText, payload.absolutePath) }
            .getOrElse { error("engine unavailable: ${it.message}") }
        check(result == "ok") { result.removePrefix("error: ") }
        payload.consumeText()
    }
}
