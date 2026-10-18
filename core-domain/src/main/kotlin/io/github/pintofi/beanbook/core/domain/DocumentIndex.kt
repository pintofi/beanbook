package io.github.pintofi.beanbook.core.domain

/** What the ledger and the filesystem together say about one document. */
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
    /**
     * Entries that attach this document as metadata, described as a person would name them
     *.
     *
     * A receipt attached while entering a transaction is a `document:` key on that entry, not
     * a `document` directive, so the folder-versus-directives reconciliation below called it
     * unfiled and the screen said "not linked to any account yet" about a file that was very
     * much linked.
     */
    val attachedTo: List<String> = emptyList(),
    /**
     * True when the only thing filing this document under [account] is where it sits on disk.
     *
     * Beancount turns a dated file in an account folder into a `document` directive of its
     * own. Nothing in the ledger says so, so unlinking cannot remove it, and saying "Filed
     * under ..." promised a link the file did not have.
     */
    val filedByFolder: Boolean = false,
) {
    /**
     * Whether anything written in the ledger refers to this file.
     *
     * A directive Beancount made from the folder is not a reference somebody wrote, so there
     * is nothing to unlink and nothing that unlinking could remove.
     */
    val linked: Boolean
        get() = attachedTo.isNotEmpty() || (status != DocumentStatus.UNFILED && !filedByFolder)

    /**
     * One line saying where this document stands, for the row under its name.
     *
     * Each status is worded as what the user would do about it, not as a category name: an
     * unfiled document is work left to do, and a missing one is a link that no longer
     * resolves, which is what invariant 5 exists to surface.
     */
    val summary: String
        get() = when {
            status == DocumentStatus.MISSING ->
                "Linked to ${account.orEmpty()}, but the file is missing"
            status == DocumentStatus.FILED && !filedByFolder -> "Filed under ${account.orEmpty()}"
            attachedTo.isNotEmpty() -> "Attached to " + attachedTo.joinToString("; ")
            status == DocumentStatus.FILED ->
                "Not linked. It counts under ${account.orEmpty()} because of the folder it is in"
            else -> "Not linked to any account yet"
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
        /**
         * Entries that attach a document as metadata, keyed by the path they name.
         * The Add form writes those; nothing here would otherwise know about them.
         */
        attachments: Map<String, List<String>> = emptyMap(),
    ): List<DocumentRow> {
        val base = root.trimEnd('/') + "/"
        // Beancount reports absolute paths. The disk side is relative to the project, so one
        // of them has to move; relativising the directive is the direction that keeps the
        // familiar "documents/receipt.pdf" on screen.
        fun relativise(absolute: String) =
            if (absolute.startsWith(base)) absolute.removePrefix(base) else absolute

        // One row per document and account. With `option "documents"`, Beancount finds a
        // filed PDF in the folder and reports it as a Document entry of its own, beside the
        // directive that filed it: the same file, the same account, twice -- and every scan
        // showed up twice in Organize.
        // Written directives first, so that when a file is both written into the ledger and
        // found in its account folder, the row that survives is the one the user can act on.
        val filed = directives
            .map { relativise(it.path) to it }
            .sortedBy { (_, directive) -> directive.auto }
            .distinctBy { (path, directive) -> path to directive.account }
        val present = onDisk.toSet()

        // The entry form writes a bare file name, so an attachment is matched on the leaf as
        // well as the whole path -- but only when no other document has that name. With two
        // `invoice.pdf` in different folders a bare `invoice.pdf` could mean either, and
        // showing it against both (or the wrong one) is worse than showing it against neither.
        val leafCounts = (present + filed.map { it.first })
            .groupingBy { it.substringAfterLast('/') }.eachCount()
        fun attachedTo(path: String): List<String> {
            val leaf = path.substringAfterLast('/')
            return attachments[path]
                ?: if (leafCounts[leaf] == 1) attachments[leaf].orEmpty() else emptyList()
        }

        val fromDirectives = filed.map { (path, directive) ->
            DocumentRow(
                path = path,
                status = if (path in present) DocumentStatus.FILED else DocumentStatus.MISSING,
                account = directive.account,
                date = directive.date,
                attachedTo = attachedTo(path),
                filedByFolder = directive.auto,
            )
        }
        // A file may carry several directives -- Beancount allows one document under two
        // accounts -- so it appears once per directive. Collapsing them to a single row would
        // make the second invisible, and an invisible directive cannot be corrected.
        val unfiled = (present - filed.map { it.first }.toSet()).map {
            DocumentRow(path = it, status = DocumentStatus.UNFILED, attachedTo = attachedTo(it))
        }

        return (fromDirectives + unfiled).sortedWith(
            compareBy({ it.path }, { it.account.orEmpty() }),
        )
    }
}
