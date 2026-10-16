package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.domain.TemplatePosting
import java.io.File
import java.math.BigDecimal

/**
 * Named entry templates, per project, on disk.
 *
 * The format is a small versioned text file rather than a serialization library: it is
 * readable in the diagnostics bundle, it adds no dependency to a GPL-2.0-only app, and a
 * file that cannot be understood can be reported rather than guessed at.
 *
 * ```
 * v1
 * template<TAB>Rent
 * meta<TAB>EUR<TAB>*<TAB>Landlord<TAB>Monthly rent
 * tags<TAB>home
 * links<TAB>lease-2026
 * posting<TAB>Expenses:Rent<TAB>750.00
 * posting<TAB>Assets:Bank:Giro<TAB>
 * ```
 *
 * Amounts are stored as the text the user typed and read back with [BigDecimal], so a
 * template never launders a number through a binary float.
 */
class TemplateStore(private val workspace: ProjectWorkspace) {

    /**
     * Every template for a project, in the order they were saved.
     *
     * A file this class cannot understand yields an empty list rather than an exception:
     * the entry screen must still open. [save] refuses to overwrite such a file, so an
     * unreadable one is never silently replaced.
     */
    fun load(projectId: String): List<EntryTemplate> {
        val file = fileFor(projectId)
        if (!file.isFile) return emptyList()
        val lines = file.readLines()
        if (lines.firstOrNull() != VERSION) return emptyList()

        val templates = mutableListOf<EntryTemplate>()
        var current: Builder? = null

        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val fields = line.split(FIELD).map { unescape(it) }
            when (fields.first()) {
                "template" -> {
                    current?.let { templates += it.build() }
                    current = Builder(name = fields.getOrElse(1) { "" })
                }
                "meta" -> current?.apply {
                    currency = fields.getOrElse(1) { "" }
                    flag = fields.getOrElse(2) { "*" }
                    payee = fields.getOrNull(3)?.takeIf { it.isNotEmpty() }
                    narration = fields.getOrNull(4)?.takeIf { it.isNotEmpty() }
                }
                "tags" -> current?.apply { tags = fields.drop(1).filter { it.isNotEmpty() } }
                "links" -> current?.apply { links = fields.drop(1).filter { it.isNotEmpty() } }
                "posting" -> current?.apply {
                    postings += TemplatePosting(
                        account = fields.getOrElse(1) { "" },
                        amount = fields.getOrNull(2)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { runCatching { BigDecimal(it) }.getOrNull() },
                    )
                }
                // An unknown record from a future version: skipped, not fatal. The version
                // line above is what decides whether the file is ours to read at all.
                else -> Unit
            }
        }
        current?.let { templates += it.build() }
        return templates
    }

    /** Adds or replaces [template] by name. Names are the handle the user sees, so they are unique. */
    fun save(projectId: String, template: EntryTemplate) {
        val kept = load(projectId).filterNot { it.name.equals(template.name, ignoreCase = true) }
        write(projectId, kept + template)
    }

    fun delete(projectId: String, name: String) {
        write(projectId, load(projectId).filterNot { it.name.equals(name, ignoreCase = true) })
    }

    private fun write(projectId: String, templates: List<EntryTemplate>) {
        val file = fileFor(projectId)
        check(file.isFile.not() || file.readLines().firstOrNull() == VERSION) {
            "refusing to overwrite a templates file this version cannot read: $file"
        }
        file.parentFile?.mkdirs()

        val text = buildString {
            append(VERSION).append('\n')
            templates.forEach { template ->
                record("template", template.name)
                record("meta", template.currency, template.flag, template.payee.orEmpty(), template.narration.orEmpty())
                if (template.tags.isNotEmpty()) record("tags", *template.tags.toTypedArray())
                if (template.links.isNotEmpty()) record("links", *template.links.toTypedArray())
                template.postings.forEach {
                    record("posting", it.account, it.amount?.toPlainString().orEmpty())
                }
            }
        }

        // Written whole and renamed into place: a process death mid-save leaves the previous
        // set intact rather than half a file.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun StringBuilder.record(kind: String, vararg fields: String) {
        append(kind)
        fields.forEach { append(FIELD).append(escape(it)) }
        append('\n')
    }

    private fun fileFor(projectId: String): File =
        workspace.templatesDir.resolve("${workspace.projectDir(projectId).name}.templates")

    private class Builder(val name: String) {
        var currency: String = ""
        var flag: String = "*"
        var payee: String? = null
        var narration: String? = null
        var tags: List<String> = emptyList()
        var links: List<String> = emptyList()
        val postings = mutableListOf<TemplatePosting>()

        fun build() = EntryTemplate(
            name = name,
            currency = currency,
            postings = postings.toList(),
            payee = payee,
            narration = narration,
            flag = flag,
            tags = tags,
            links = links,
        )
    }

    private companion object {
        const val VERSION = "v1"
        const val FIELD = "\t"

        // A payee may legitimately contain a tab, a newline or a backslash; the record
        // separator must survive all three, or one odd payee would corrupt the file.
        fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        fun unescape(value: String): String {
            val out = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '\\' && index + 1 < value.length) {
                    when (val next = value[index + 1]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        '\\' -> out.append('\\')
                        else -> out.append(char).append(next)
                    }
                    index += 2
                } else {
                    out.append(char)
                    index++
                }
            }
            return out.toString()
        }
    }
}
