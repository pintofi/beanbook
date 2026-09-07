package io.github.pintofi.beanbook.core.domain

import java.security.MessageDigest
import java.time.LocalDate

/**
 * Where a captured document lives inside a project, and what it is called.
 *
 * The layout is Beancount's own documents convention —
 * `documents/Expenses/Food/Groceries/2026-08-24.a1b2c3d4.pdf` — so a project opened on a
 * desktop with `option "documents" "documents"` finds the same files by itself. We are not
 * inventing a filing scheme; we are writing into the one that already exists.
 *
 * Two properties the tests hold this to, both of them invariants rather than niceties:
 *
 *  * **Deterministic.** The name is derived from the account, the date and a hash of the
 *    *content*. Saving the same capture twice produces the same path, so a retried write
 *    cannot leave two copies of one receipt behind (CLAUDE.md, invariant 6).
 *  * **Inside the project.** Every segment comes from a validated account name or a hex
 *    hash, so a document link can never point outside the project tree (invariant 5).
 */
data class DocumentName(
    val account: String,
    val date: LocalDate,
    val contentHash: String,
    val extension: String,
    /** Distinguishes two different files that share a date, account and hash prefix. */
    val ordinal: Int = 0,
    /**
     * What the user called it, if anything (B57).
     *
     * A name derived from content alone is unimpeachable and unreadable: a folder of
     * `2026-09-07.a1b2c3d4.pdf` tells nobody which one is the car insurance. The label sits
     * between the date and the hash, so the hash is still there and the ordering by date
     * still holds -- naming a document changes how it reads, not how it is found.
     */
    val label: String? = null,
) {

    /** The path relative to the project root, which is exactly what goes in the ledger. */
    val relativePath: String
        get() = buildString {
            append(ROOT).append('/')
            append(account.replace(':', '/')).append('/')
            append(date).append('.')
            label?.takeIf { it.isNotBlank() }?.let { append(it).append('.') }
            append(contentHash)
            if (ordinal > 0) append('-').append(ordinal)
            append('.').append(extension)
        }

    /** The next candidate when [relativePath] is taken by *different* content. */
    fun next(): DocumentName = copy(ordinal = ordinal + 1)

    companion object {
        const val ROOT = "documents"

        /** Characters of the content hash kept in the filename. */
        const val HASH_LENGTH = 12

        private val EXTENSION = Regex("[a-z0-9]{1,8}")

        /** As much of a name as can go in a filename without needing to be escaped. */
        const val LABEL_LENGTH = 40

        /**
         * [raw] as a filename segment, or null when nothing usable is left.
         *
         * Everything outside letters, digits and dashes becomes a dash, because this ends up
         * in a path that a `document` directive must be able to quote and a desktop
         * Beancount must be able to open. A name is a convenience; it may not be a way to
         * write outside the project (invariant 5).
         */
        fun label(raw: String?): String? = raw
            ?.trim()
            ?.lowercase()
            ?.map { if (it.isLetterOrDigit()) it else '-' }
            ?.joinToString("")
            ?.split('-')
            ?.filter { it.isNotBlank() }
            ?.joinToString("-")
            ?.take(LABEL_LENGTH)
            ?.trim('-')
            ?.takeIf { it.isNotBlank() }

        /**
         * The name for [content] filed against [account] on [date].
         *
         * @throws IllegalArgumentException if the account is not a valid Beancount account
         *   or the extension is not a plain lower-case one — both would otherwise be a route
         *   to a path this app is not allowed to write.
         */
        fun forContent(
            account: String,
            date: LocalDate,
            content: ByteArray,
            extension: String = "pdf",
            label: String? = null,
        ): DocumentName {
            require(BeancountText.ACCOUNT.matches(account)) {
                "\"$account\" is not a valid account name"
            }
            require(EXTENSION.matches(extension)) {
                "\"$extension\" is not a usable file extension"
            }
            return DocumentName(
                account = account,
                date = date,
                contentHash = hash(content),
                extension = extension,
                label = label(label),
            )
        }

        /** SHA-256 of the content, truncated — the same bytes always give the same name. */
        fun hash(content: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { "%02x".format(it) }
                .take(HASH_LENGTH)
    }
}

/**
 * A link from an entry to a document, and the two very different ways of removing one.
 *
 * Unlinking removes the reference and leaves the file; deleting removes the file itself.
 * They are separate operations with separately worded confirmations because someone who
 * meant "this receipt is attached to the wrong transaction" must not lose the receipt
 * (SPEC.md, J3).
 */
enum class DocumentRemoval {
    /** Drop the `document` directive or metadata; the file stays on disk. */
    UNLINK,

    /** Delete the file as well. Destructive, and named as such wherever it is offered. */
    DELETE_FILE,
    ;

    fun describe(path: String): String = when (this) {
        UNLINK ->
            "Unlink this document from the entry. The file stays in the project at $path " +
                "and can be linked again."
        DELETE_FILE ->
            "Delete $path from this device. The file itself goes, not just the link, and " +
                "the entry stops referring to it."
    }
}
