package dev.beancount.core.domain

/** What the ledger and the filesystem together say about one document (B18). */
enum class DocumentStatus {
    /** On disk, and a `document` directive files it under an account. */
    FILED,

    /** On disk, but nothing in the ledger refers to it. Bookkeeping left half-done. */
    UNFILED,

    /** A directive names it, but the file is not there. A broken link, per invariant 5. */
    MISSING,
}

/**
 * One row of the Organize list: a document, where it lives, and what the ledger says of it.
 *
 * [path] is relative to the project when the file is inside it, because that is what the
 * user recognises and what the `document` directive was written as. A document elsewhere on
 * the device keeps its absolute path — shortening it would name a file that is not there.
 */
data class DocumentRow(
    val path: String,
    val status: DocumentStatus,
    val account: String? = null,
    val date: String? = null,
) {
    /**
     * One line saying where this document stands, for the row under its name.
     *
     * Each status is worded as what the user would do about it, not as a category name: an
     * unfiled document is work left to do, and a missing one is a link that no longer
     * resolves, which is what invariant 5 exists to surface.
     */
    val summary: String
        get() = when (status) {
            DocumentStatus.FILED -> "Filed under ${account.orEmpty()}"
            DocumentStatus.UNFILED -> "Not linked to any account yet"
            DocumentStatus.MISSING -> "Linked to ${account.orEmpty()}, but the file is missing"
        }
}

/**
 * Reconciles the files in a project's document folder with the ledger's `document`
 * directives.
 *
 * The screen used to list the folder alone, which cannot answer either question worth
 * asking: what is this filed under, and is anything filed at all. Neither source is
 * sufficient by itself — the folder knows what exists, the ledger knows what it means.
 *
 * Nothing is dropped from either side. A file with no directive is [DocumentStatus.UNFILED]
 * rather than absent, and a directive with no file is [DocumentStatus.MISSING] rather than
 * silently ignored: the second is exactly the broken link invariant 5 exists to catch, and
 * hiding it would let the app look tidy while the ledger was wrong.
 */
object DocumentIndex {

    fun reconcile(
        root: String,
        onDisk: List<String>,
        directives: List<DocumentDirective>,
    ): List<DocumentRow> {
        val base = root.trimEnd('/') + "/"
        // Beancount reports absolute paths. The disk side is relative to the project, so one
        // of them has to move; relativising the directive is the direction that keeps the
        // familiar "documents/receipt.pdf" on screen.
        fun relativise(absolute: String) =
            if (absolute.startsWith(base)) absolute.removePrefix(base) else absolute

        val filed = directives.map { relativise(it.path) to it }
        val present = onDisk.toSet()

        val fromDirectives = filed.map { (path, directive) ->
            DocumentRow(
                path = path,
                status = if (path in present) DocumentStatus.FILED else DocumentStatus.MISSING,
                account = directive.account,
                date = directive.date,
            )
        }
        // A file may carry several directives -- Beancount allows one document under two
        // accounts -- so it appears once per directive. Collapsing them to a single row would
        // make the second invisible, and an invisible directive cannot be corrected.
        val unfiled = (present - filed.map { it.first }.toSet()).map {
            DocumentRow(path = it, status = DocumentStatus.UNFILED)
        }

        return (fromDirectives + unfiled).sortedWith(
            compareBy({ it.path }, { it.account.orEmpty() }),
        )
    }
}
