package io.github.pintofi.beanbook.core.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Why a link was not followed. */
enum class LinkProblem {
    NOT_FOUND,
    NO_MATCH,
    ABSOLUTE,

    /** It points above the folder the person has allowed; [UnresolvedLink.levelsUp] says how far. */
    OUTSIDE,
    NOT_TEXT,

    /** Two linked files differ only in letter case, which a phone's file system keeps apart. */
    CASE_COLLISION,
}

/** What a linked file is, which decides how it is shown and how it is read. */
enum class LinkedKind { MAIN, LEDGER, DOCUMENT, CODE }

data class LinkedFile(val path: String, val kind: LinkedKind, val size: Long)

/** One link that could not be followed, as the person would find it: where it is written and what it says. */
data class UnresolvedLink(
    val file: String,
    val line: Int,
    val text: String,
    val problem: LinkProblem,
    val levelsUp: Int = 0,
)

/**
 * The files that make a ledger: the one the person chose and everything it links to.
 *
 * Paths are in the main file's coordinates (see [LinkedSource]); [levelsUp] is how far above the main
 * file's folder the files reach, so the caller knows which folder has to be read as the project's base.
 * [neededUp] is how far a link *wanted* to reach, which may be more than was allowed.
 */
data class LinkedLedger(
    val main: String,
    val files: List<LinkedFile>,
    val unresolved: List<UnresolvedLink>,
    val levelsUp: Int,
    val neededUp: Int,
    val truncated: Boolean,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

/**
 * Follows a Beancount ledger's links from the file the person chose, without reading anything else.
 *
 * What counts as a link, checked against Beancount 3.2.3 and Fava 1.30:
 *
 *  - **Ledger files**: `include "path"` at column 0 (with Python-style globs), in the main file and,
 *    recursively, in every included file, relative to the folder of the file that writes it; and
 *    Fava's `custom "fava-option" "default-file" "path"`, relative to the main file's folder.
 *  - **Attachments**: `document` directives and `document:` metadata, relative to the folder of the file
 *    that writes them; and `option "documents"` folders, of which only the files Beancount's documents
 *    plugin would read are brought (under account-named subfolders, named `YYYY-MM-DD.…`).
 *  - **Code**, only when Beancount or Fava would load it from beside the ledger: a `plugin` or
 *    `custom "fava-extension"` module when `option "insert_pythonpath"` is `TRUE`, and Fava's
 *    `import-config` file.
 *
 * Not followed: Python `import`s inside code files, Fava `import-dirs`, `pluginconfig` strings, and
 * anything inside an attachment. An absolute path is reported, never followed. A link that reaches
 * above [maxUp] folders over the main file's is reported as outside, with how far it wanted to reach.
 *
 * Reading is bounded by [maxFiles] and [maxLedgerBytes]; a file that is not UTF-8 text is reported, not
 * read as a ledger. Include cycles end at the first revisit.
 */
class LedgerLinks(
    private val source: LinkedSource,
    private val maxUp: Int = 0,
    private val maxFiles: Int = 5_000,
    private val maxLedgerBytes: Long = 200_000_000L,
) {

    private val files = LinkedHashMap<String, LinkedFile>()
    private val byFolded = HashMap<String, String>()
    private val unresolved = mutableListOf<UnresolvedLink>()
    private val queue = ArrayDeque<Pair<String, String>>()
    private var ledgerBytes = 0L
    private var neededUp = 0
    private var truncated = false

    fun walk(main: String): LinkedLedger {
        val mainPath = LinkPath.normalise(main)
        add(mainPath, LinkedKind.MAIN, mainPath, 0, mainPath)
        var mainText = ""
        val texts = LinkedHashMap<String, String>()
        while (queue.isNotEmpty()) {
            val (path, text) = queue.removeFirst()
            texts[path] = text
            if (path == mainPath) mainText = text
        }
        // Options are read from the main file alone, and `insert_pythonpath` may come after the plugin
        // lines that depend on it, so the main file is read for them first.
        val options = optionsOf(mainText)
        val pythonPath = options["insert_pythonpath"].orEmpty().any { it.equals("TRUE", ignoreCase = true) }
        val documentRoots = options["documents"].orEmpty()

        val pending = ArrayDeque(texts.entries.map { it.key to it.value })
        while (pending.isNotEmpty()) {
            val (path, text) = pending.removeFirst()
            scan(path, text, mainPath, pythonPath)
            while (queue.isNotEmpty()) pending += queue.removeFirst()
        }
        documentRoots.forEach { followDocumentsRoot(mainPath, it) }
        val reach = files.keys.maxOfOrNull { LinkPath.levelsUp(it) } ?: 0
        return LinkedLedger(
            main = mainPath,
            files = files.values.toList(),
            unresolved = unresolved.toList(),
            levelsUp = reach,
            neededUp = maxOf(neededUp, reach),
            truncated = truncated,
        )
    }

    // -- reading a ledger file --------------------------------------------------------------------

    private fun scan(path: String, text: String, main: String, pythonPath: Boolean) {
        val from = LinkPath.directoryOf(path)
        val mainDir = LinkPath.directoryOf(main)
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trimEnd('\r')
            val number = index + 1
            when {
                line.startsWith("include") && INCLUDE.containsMatchIn(line) -> {
                    val link = firstString(line.substring("include".length)) ?: return@forEachIndexed
                    if (link.isNotBlank()) follow(link, from, LinkedKind.LEDGER, glob = true, path, number, line.trim())
                }
                line.startsWith("plugin") && PLUGIN.containsMatchIn(line) ->
                    if (pythonPath) firstString(line.substring("plugin".length))?.let {
                        followModule(it, mainDir, path, number, line.trim())
                    }
                DIRECTIVE_DATE.containsMatchIn(line) -> dated(line, number, path, from, mainDir, pythonPath)
                METADATA_DOCUMENT.containsMatchIn(line) -> {
                    val value = firstString(line.substringAfter(':')) ?: return@forEachIndexed
                    // A bare name is looked up among the documents already found; only a path that was
                    // written with a folder in it is a link that can be wrong.
                    if ('/' in value) {
                        follow(value, from, LinkedKind.DOCUMENT, glob = false, path, number, line.trim())
                    } else if (stat(LinkPath.resolve(from, value))?.isDirectory == false) {
                        follow(value, from, LinkedKind.DOCUMENT, glob = false, path, number, line.trim())
                    }
                }
            }
        }
    }

    /** A line that starts with a date: `document` and Fava's `custom` directives. */
    private fun dated(line: String, number: Int, path: String, from: String, mainDir: String, pythonPath: Boolean) {
        val rest = line.substring(10).trimStart()
        when {
            rest.startsWith("document") && DOCUMENT_DIRECTIVE.containsMatchIn(line) -> {
                val link = lastString(line) ?: return
                if (link.isNotBlank()) follow(link, from, LinkedKind.DOCUMENT, glob = false, path, number, line.trim())
            }
            rest.startsWith("custom") -> {
                val strings = allStrings(rest.substring("custom".length))
                when {
                    strings.size >= 3 && strings[0] == "fava-option" && strings[1] == "default-file" ->
                        follow(strings[2], mainDir, LinkedKind.LEDGER, glob = false, path, number, line.trim())
                    strings.size >= 3 && strings[0] == "fava-option" && strings[1] == "import-config" ->
                        follow(strings[2], mainDir, LinkedKind.CODE, glob = false, path, number, line.trim())
                    strings.size >= 2 && strings[0] == "fava-extension" && pythonPath ->
                        followModule(strings[1], mainDir, path, number, line.trim())
                }
            }
        }
    }

    // -- following one link -------------------------------------------------------------------------

    private fun follow(
        link: String,
        fromDir: String,
        kind: LinkedKind,
        glob: Boolean,
        file: String,
        line: Int,
        text: String,
    ) {
        if (LinkPath.isAbsolute(link)) {
            unresolved += UnresolvedLink(file, line, text, LinkProblem.ABSOLUTE)
            return
        }
        val resolved = LinkPath.resolve(fromDir, link)
        val up = LinkPath.levelsUp(resolved)
        if (up > maxUp) {
            neededUp = maxOf(neededUp, up)
            unresolved += UnresolvedLink(file, line, text, LinkProblem.OUTSIDE, levelsUp = up)
            return
        }
        if (glob && LinkGlob.hasMagic(link)) {
            val matches = LinkGlob.expand(source, fromDir, link, maxFiles)
                .filter { LinkPath.levelsUp(it) <= maxUp }
            if (matches.isEmpty()) {
                unresolved += UnresolvedLink(file, line, text, LinkProblem.NO_MATCH)
            } else {
                matches.forEach { add(it, kind, file, line, text) }
            }
            return
        }
        val stat = source.stat(resolved)
        if (stat == null || stat.isDirectory) {
            unresolved += UnresolvedLink(file, line, text, LinkProblem.NOT_FOUND)
        } else {
            add(resolved, kind, file, line, text)
        }
    }

    /** `a.b.c` as Python would find it beside the main file: `a/b/c.py`, or the package folder `a/b/c/`. */
    private fun followModule(module: String, mainDir: String, file: String, line: Int, text: String) {
        val parts = module.split('.')
        if (parts.any { it.isEmpty() || !it.all { c -> c.isLetterOrDigit() || c == '_' } }) return
        val base = LinkPath.resolve(mainDir, parts.joinToString("/"))
        if (LinkPath.levelsUp(base) > maxUp) return
        when {
            source.stat("$base.py")?.isDirectory == false -> add("$base.py", LinkedKind.CODE, file, line, text)
            source.stat(base)?.isDirectory == true ->
                pythonFiles(base).forEach { add(it, LinkedKind.CODE, file, line, text) }
            // A module that is simply installed (a stock plugin) is not beside the ledger: nothing to bring.
        }
    }

    private fun pythonFiles(dir: String): List<String> = buildList {
        fun walk(current: String) {
            for (child in source.children(current).orEmpty().sortedBy { it.name }) {
                if (child.name.startsWith('.') || size > maxFiles) continue
                val path = LinkPath.resolve(current, child.name)
                if (child.isDirectory) walk(path) else if (child.name.endsWith(".py")) add(path)
            }
        }
        walk(dir)
    }

    /**
     * The files of an `option "documents"` folder that Beancount's documents plugin reads: a file named
     * `YYYY-MM-DD.…` in a subfolder whose path, with `/` for `:`, is an account name.
     */
    private fun followDocumentsRoot(main: String, root: String) {
        if (LinkPath.isAbsolute(root)) {
            unresolved += UnresolvedLink(main, 0, "option \"documents\" \"$root\"", LinkProblem.ABSOLUTE)
            return
        }
        val base = LinkPath.resolve(LinkPath.directoryOf(main), root)
        if (LinkPath.levelsUp(base) > maxUp) {
            neededUp = maxOf(neededUp, LinkPath.levelsUp(base))
            unresolved += UnresolvedLink(
                main, 0, "option \"documents\" \"$root\"", LinkProblem.OUTSIDE, LinkPath.levelsUp(base),
            )
            return
        }
        fun walk(dir: String, account: List<String>) {
            val children = source.children(dir) ?: return
            for (child in children.sortedBy { it.name }) {
                if (files.size >= maxFiles) {
                    truncated = true
                    return
                }
                val path = LinkPath.resolve(dir, child.name)
                if (child.isDirectory) {
                    walk(path, account + child.name)
                } else if (account.size >= 2 && DOCUMENT_NAME.matches(child.name) &&
                    BeancountText.ACCOUNT.matches(account.joinToString(":"))
                ) {
                    add(path, LinkedKind.DOCUMENT, main, 0, "option \"documents\" \"$root\"")
                }
            }
        }
        // The first level under the root is the account's type (Assets, Expenses), so a subfolder of the
        // root counts as part of the account name from its first segment.
        for (top in source.children(base).orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            walk(LinkPath.resolve(base, top.name), listOf(top.name))
        }
    }

    // -- recording a file ---------------------------------------------------------------------------

    private fun add(path: String, kind: LinkedKind, from: String, line: Int, text: String) {
        if (path in files) return
        if (files.size >= maxFiles) {
            truncated = true
            return
        }
        val folded = path.lowercase()
        val earlier = byFolded[folded]
        if (earlier != null && earlier != path) {
            unresolved += UnresolvedLink(from, line, text, LinkProblem.CASE_COLLISION)
            return
        }
        if (kind == LinkedKind.MAIN || kind == LinkedKind.LEDGER) {
            val bytes = source.read(path)
            if (bytes == null) {
                unresolved += UnresolvedLink(from, line, text, LinkProblem.NOT_FOUND)
                return
            }
            val decoded = decode(bytes)
            if (decoded == null) {
                unresolved += UnresolvedLink(from, line, text, LinkProblem.NOT_TEXT)
                return
            }
            ledgerBytes += bytes.size
            if (ledgerBytes > maxLedgerBytes) {
                truncated = true
                return
            }
            files[path] = LinkedFile(path, kind, bytes.size.toLong())
            byFolded[folded] = path
            queue += path to decoded
        } else {
            files[path] = LinkedFile(path, kind, source.stat(path)?.size ?: 0L)
            byFolded[folded] = path
        }
    }

    private fun stat(path: String) = source.stat(path)

    /** Valid UTF-8 with no NUL bytes, which is all Beancount asks of a ledger file. */
    private fun decode(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    // -- reading directive text -----------------------------------------------------------------------

    /** The `option "name" "value"` lines of [text], by name; Beancount reads options from the main file only. */
    private fun optionsOf(text: String): Map<String, List<String>> {
        val found = LinkedHashMap<String, MutableList<String>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (!line.startsWith("option")) return@forEach
            val strings = allStrings(line.substring("option".length))
            if (strings.size >= 2) found.getOrPut(strings[0]) { mutableListOf() } += strings[1]
        }
        return found
    }

    private fun allStrings(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                ';' -> return out
                '"' -> {
                    val (value, next) = BeancountText.unquoteAt(text, i) ?: return out
                    out += value
                    i = next
                }
                else -> i++
            }
        }
        return out
    }

    private fun firstString(text: String): String? = allStrings(text).firstOrNull()

    private fun lastString(text: String): String? = allStrings(text).lastOrNull()

    private companion object {
        val INCLUDE = Regex("""^include[ \t]+"""")
        val PLUGIN = Regex("""^plugin[ \t]+"""")
        val DIRECTIVE_DATE = Regex("""^\d{4}-\d{2}-\d{2}[ \t]""")
        val DOCUMENT_DIRECTIVE = Regex("""^\d{4}-\d{2}-\d{2}[ \t]+document[ \t]""")
        val METADATA_DOCUMENT = Regex("""^[ \t]+document(-\d+)?[ \t]*:[ \t]*"""")
        // Beancount matches `(\d\d\d\d)-(\d\d)-(\d\d).(.*)` from the start: the "." is any character, so
        // a name needs at least one character after the date, and it need not be a dot.
        val DOCUMENT_NAME = Regex("""\d{4}-\d{2}-\d{2}(?s:.).*""")
    }
}
