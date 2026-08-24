package dev.beancount.core.data

import java.io.File
import java.time.LocalDate

/**
 * The app-private workspace (docs/storage-and-backup.md §1).
 *
 * The ledger lives here because Fava and Beancount need real POSIX paths and Android
 * hands out `content://` URIs. Runtime state — snapshots, journal, drafts, caches — is
 * kept **outside** the portable project tree so an exported project is clean.
 *
 * ```
 * <root>/projects/<id>/     canonical portable project tree
 * <root>/snapshots/<id>/    pre-write snapshots (M4)
 * <root>/journal/<id>.wal   write-ahead journal (M4)
 * <root>/drafts/<id>/       unsaved editor buffers
 * <root>/templates/<id>     named entry templates
 * <root>/cache/<id>/        parse and widget caches, never backed up
 * ```
 */
class ProjectWorkspace(private val root: File) {

    val projectsDir: File get() = root.resolve("projects")
    val snapshotsDir: File get() = root.resolve("snapshots")
    val journalDir: File get() = root.resolve("journal")
    val draftsDir: File get() = root.resolve("drafts")

    /**
     * Entry templates. App state rather than ledger data: they are prefill for the entry
     * form, not directives, so they stay out of the portable tree and out of exports. A
     * backup (M9) carries them; an exported ledger folder does not.
     */
    val templatesDir: File get() = root.resolve("templates")
    val cacheDir: File get() = root.resolve("cache")

    fun projectDir(id: String): File = projectsDir.resolve(requireValidId(id))

    /** The root ledger file Fava is pointed at. */
    fun mainFile(id: String): File = projectDir(id).resolve(MAIN_FILE)

    fun listProjects(): List<String> =
        projectsDir.listFiles()
            ?.filter { it.isDirectory && it.resolve(MAIN_FILE).isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun exists(id: String): Boolean = mainFile(id).isFile

    /**
     * Creates the starter layout described in docs/storage-and-backup.md §2.
     *
     * These filenames are a convention for new projects only — imported projects keep
     * their own shape, and the app discovers structure from the root file's include graph.
     *
     * @return the main ledger file.
     */
    fun createProject(
        id: String,
        title: String = id,
        operatingCurrency: String = "USD",
        today: LocalDate = LocalDate.now(),
    ): File {
        val dir = projectDir(id)
        require(!dir.exists()) { "project already exists: $id" }

        listOf("files", "documents", "prices", "scripts", "plugins").forEach {
            dir.resolve(it).mkdirs()
        }

        dir.resolve(MAIN_FILE).writeText(mainTemplate(title, operatingCurrency))
        dir.resolve("files/accounts.beancount").writeText(accountsTemplate(today, operatingCurrency))
        dir.resolve("files/transactions.beancount").writeText(TRANSACTIONS_TEMPLATE)
        dir.resolve("files/commodities.beancount").writeText(
            commoditiesTemplate(today, operatingCurrency)
        )

        return mainFile(id)
    }

    /**
     * Project ids become directory names, so they are validated rather than trusted:
     * no traversal, no separators, no hidden files, no reserved names.
     */
    private fun requireValidId(id: String): String {
        require(id.isNotBlank()) { "project id must not be blank" }
        require(id.length <= MAX_ID_LENGTH) { "project id too long" }
        require(id !in RESERVED_IDS) { "reserved project id: $id" }
        require(!id.startsWith(".")) { "project id must not start with a dot: $id" }
        require(id.all { it.isLetterOrDigit() || it in "-_" }) {
            "project id may only contain letters, digits, hyphen and underscore: $id"
        }
        return id
    }

    private fun mainTemplate(title: String, currency: String): String = """
        ; ${title}
        ;
        ; This file is plain text and yours to edit, here or on a desktop. The app never
        ; rewrites parts of it that you did not change.

        option "title" "${title.replace("\"", "\\\"")}"
        option "operating_currency" "$currency"

        include "files/commodities.beancount"
        include "files/accounts.beancount"
        include "files/transactions.beancount"
    """.trimIndent() + "\n"

    private fun accountsTemplate(today: LocalDate, currency: String): String = """
        ; Accounts must be opened before they are used.

        $today open Assets:Cash                 $currency
        $today open Assets:Bank:Checking        $currency
        $today open Expenses:Food:Groceries     $currency
        $today open Expenses:Home:Rent          $currency
        $today open Expenses:Transport          $currency
        $today open Income:Salary               $currency
        $today open Equity:Opening-Balances     $currency
    """.trimIndent() + "\n"

    private fun commoditiesTemplate(today: LocalDate, currency: String): String = """
        $today commodity $currency
    """.trimIndent() + "\n"

    companion object {
        const val MAIN_FILE = "main.beancount"
        private const val MAX_ID_LENGTH = 64
        private val RESERVED_IDS = setOf(".", "..")

        private val TRANSACTIONS_TEMPLATE = """
            ; Your transactions. Add them here, or let the app write them for you.
        """.trimIndent() + "\n"
    }
}
