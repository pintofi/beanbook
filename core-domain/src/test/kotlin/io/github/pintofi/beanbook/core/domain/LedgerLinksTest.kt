package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerLinksTest {

    private fun walk(
        vararg files: Pair<String, String>,
        main: String = "hmw",
        mainDir: String = "",
        maxUp: Int = 0,
    ): Pair<LinkedLedger, MapLinkedSource> {
        val source = MapLinkedSource(mainDir, *files)
        return LedgerLinks(source, maxUp).walk(main) to source
    }

    private fun LinkedLedger.paths() = files.map { it.path }.sorted()
    private fun LinkedLedger.problems() = unresolved.map { it.problem }

    @Test
    fun `a file with no links is the whole ledger, whatever it is called`() {
        for (name in listOf("main.beancount", "hmw.bean", "hmw")) {
            val (ledger, _) = walk(name to "option \"title\" \"x\"\n", main = name)

            assertEquals(listOf(name), ledger.paths())
            assertEquals(LinkedKind.MAIN, ledger.files.single().kind)
            assertTrue(ledger.unresolved.isEmpty())
        }
    }

    @Test
    fun `includes are followed through folders and keep their place`() {
        val (ledger, _) = walk(
            "hmw" to "include \"accounts/open.bean\"\ninclude \"2025/jan.bean\"\n",
            "accounts/open.bean" to "include \"../shared.bean\"\n",
            "shared.bean" to "; shared\n",
            "2025/jan.bean" to "",
            "unrelated.bean" to "",
        )

        assertEquals(listOf("2025/jan.bean", "accounts/open.bean", "hmw", "shared.bean"), ledger.paths())
    }

    @Test
    fun `an include is relative to the file that writes it, not to the main file`() {
        val (ledger, _) = walk(
            "hmw" to "include \"books/b.bean\"\n",
            "books/b.bean" to "include \"c.bean\"\n",
            "books/c.bean" to "",
            "c.bean" to "wrong",
        )

        assertEquals(listOf("books/b.bean", "books/c.bean", "hmw"), ledger.paths())
    }

    @Test
    fun `files that include each other end at the first revisit`() {
        val (ledger, _) = walk("hmw" to "include \"a\"\n", "a" to "include \"hmw\"\ninclude \"a\"\n")

        assertEquals(listOf("a", "hmw"), ledger.paths())
    }

    @Test
    fun `only a directive at column zero is an include`() {
        val (ledger, _) = walk(
            "hmw" to "  include \"indented\"\n; include \"commented\"\ninclude \"real\"\nincluded \"nope\"\n",
            "indented" to "", "commented" to "", "real" to "",
        )

        assertEquals(listOf("hmw", "real"), ledger.paths())
    }

    @Test
    fun `a glob brings the files it matches, sorted, and nothing it does not`() {
        val (ledger, _) = walk(
            "hmw" to "include \"2024/*.bean\"\n",
            "2024/jan.bean" to "", "2024/feb.bean" to "", "2024/.hid.bean" to "", "2024/note.txt" to "",
            "2024/q1/mar.bean" to "", "2025/x.bean" to "",
        )

        assertEquals(listOf("2024/feb.bean", "2024/jan.bean", "hmw"), ledger.paths())
    }

    @Test
    fun `links that cannot be followed are reported where they are written`() {
        val (ledger, _) = walk(
            "hmw" to "include \"missing.bean\"\ninclude \"none/*.bean\"\ninclude \"/etc/x.bean\"\n",
        )

        assertEquals(listOf(LinkProblem.NOT_FOUND, LinkProblem.NO_MATCH, LinkProblem.ABSOLUTE), ledger.problems())
        assertEquals(listOf(1, 2, 3), ledger.unresolved.map { it.line })
        assertEquals("include \"missing.bean\"", ledger.unresolved.first().text)
        assertEquals("hmw", ledger.unresolved.first().file)
    }

    @Test
    fun `a link above the allowed folders says how far it wanted to reach`() {
        val files = arrayOf(
            "books/hmw" to "include \"../shared.bean\"\ninclude \"../../top.bean\"\n",
            "shared.bean" to "", "top.bean" to "",
        )

        val (none, _) = walk(*files, main = "hmw", mainDir = "books", maxUp = 0)
        assertEquals(listOf("hmw"), none.paths())
        assertEquals(listOf(LinkProblem.OUTSIDE, LinkProblem.OUTSIDE), none.problems())
        assertEquals(2, none.neededUp)

        val (one, _) = walk(*files, main = "hmw", mainDir = "books", maxUp = 1)
        assertEquals(listOf("../shared.bean", "hmw"), one.paths())
        assertEquals(1, one.levelsUp)
        assertEquals(2, one.neededUp)
    }

    @Test
    fun `a file that is not text is reported and never read as a ledger`() {
        val source = MapLinkedSource(
            mapOf(
                "hmw" to "include \"bin\"\ninclude \"nul\"\ninclude \"ok\"\n".toByteArray(),
                "bin" to byteArrayOf(0x66, 0xC3.toByte(), 0x28),
                "nul" to byteArrayOf(0x61, 0x00, 0x62),
                "ok" to "fine".toByteArray(),
            ),
        )

        val ledger = LedgerLinks(source).walk("hmw")

        assertEquals(listOf("hmw", "ok"), ledger.paths())
        assertEquals(listOf(LinkProblem.NOT_TEXT, LinkProblem.NOT_TEXT), ledger.problems())
    }

    @Test
    fun `two links that differ only in letter case are not both imported`() {
        val (ledger, _) = walk("hmw" to "include \"A.bean\"\ninclude \"a.bean\"\n", "A.bean" to "", "a.bean" to "")

        assertEquals(listOf("A.bean", "hmw"), ledger.paths())
        assertEquals(listOf(LinkProblem.CASE_COLLISION), ledger.problems())
    }

    @Test
    fun `document directives and metadata are followed from the folder of the file that writes them`() {
        val (ledger, _) = walk(
            "hmw" to "include \"books/b.bean\"\n2024-01-05 document Assets:Bank \"scans/a.pdf\"\n" +
                "2024-01-06 * \"x\"\n  document: \"scans/b.pdf\"\n  document-2: \"loose.pdf\"\n",
            "books/b.bean" to "2024-02-01 document Assets:Bank \"c.pdf\"\n",
            "books/c.pdf" to "", "scans/a.pdf" to "", "scans/b.pdf" to "", "loose.pdf" to "",
        )

        assertEquals(
            listOf("books/b.bean", "books/c.pdf", "hmw", "loose.pdf", "scans/a.pdf", "scans/b.pdf"),
            ledger.paths(),
        )
        val kinds = ledger.files.filter { it.path.endsWith(".pdf") }.map { it.kind }.toSet()
        assertEquals(setOf(LinkedKind.DOCUMENT), kinds)
    }

    @Test
    fun `a metadata document name with no folder is not an error when it is not there`() {
        val (ledger, _) = walk("hmw" to "2024-01-06 * \"x\"\n  document: \"in-a-documents-folder.pdf\"\n")

        assertTrue(ledger.unresolved.isEmpty())
        val (withPath, _) = walk("hmw" to "2024-01-06 * \"x\"\n  document: \"scans/gone.pdf\"\n")
        assertEquals(listOf(LinkProblem.NOT_FOUND), withPath.problems())
    }

    @Test
    fun `a documents folder brings only the files the documents plugin reads`() {
        val (ledger, source) = walk(
            "hmw" to "option \"documents\" \"docs\"\n",
            "docs/Assets/Bank/2024-01-05.receipt.pdf" to "",
            "docs/Assets/Bank/notes.pdf" to "",
            "docs/Expenses/Food/2024-03-01.menu.jpg" to "",
            "docs/loose/2024-03-02.x.pdf" to "",
            "docs/lowercase/bank/2024-03-02.y.pdf" to "",
            "elsewhere/2024-03-02.z.pdf" to "",
        )

        assertEquals(
            listOf("docs/Assets/Bank/2024-01-05.receipt.pdf", "docs/Expenses/Food/2024-03-01.menu.jpg", "hmw"),
            ledger.paths(),
        )
        assertFalse("elsewhere" in source.listed)
    }

    @Test
    fun `options are read from the main file alone`() {
        val (ledger, _) = walk(
            "hmw" to "include \"b.bean\"\n",
            "b.bean" to "option \"documents\" \"docs\"\n",
            "docs/Assets/Bank/2024-01-05.a.pdf" to "",
        )

        assertEquals(listOf("b.bean", "hmw"), ledger.paths())
    }

    @Test
    fun `plugin modules and extensions beside the ledger come only with insert_pythonpath`() {
        val body = "plugin \"myplug\"\n2024-01-01 custom \"fava-extension\" \"myext\"\n" +
            "plugin \"beancount.plugins.auto\"\n"
        val files = arrayOf(
            "myplug.py" to "", "myext/__init__.py" to "", "myext/util.py" to "", "myext/data.json" to "",
        )

        val (without, _) = walk("hmw" to body, *files)
        assertEquals(listOf("hmw"), without.paths())

        val (with, _) = walk("hmw" to "option \"insert_pythonpath\" \"TRUE\"\n$body", *files)
        assertEquals(listOf("hmw", "myext/__init__.py", "myext/util.py", "myplug.py"), with.paths())
        assertEquals(setOf(LinkedKind.CODE), with.files.drop(1).map { it.kind }.toSet())
    }

    @Test
    fun `fava's default file and import config are followed from the main file's folder`() {
        val (ledger, _) = walk(
            "hmw" to "2024-01-01 custom \"fava-option\" \"default-file\" \"journal.bean\"\n" +
                "2024-01-01 custom \"fava-option\" \"import-config\" \"import.py\"\n",
            "journal.bean" to "include \"more.bean\"\n", "more.bean" to "", "import.py" to "",
        )

        assertEquals(listOf("hmw", "import.py", "journal.bean", "more.bean"), ledger.paths())
    }

    @Test
    fun `nothing the links do not name is read, and a folder is listed only where a link points into it`() {
        val (ledger, source) = walk(
            "hmw" to "include \"a.bean\"\ninclude \"glob/*.bean\"\n",
            "a.bean" to "", "glob/x.bean" to "", "secret/passwords.txt" to "", "other.bean" to "",
        )

        assertEquals(listOf("a.bean", "glob/x.bean", "hmw"), ledger.paths())
        assertFalse(source.reads.any { "secret" in it || it == "other.bean" })
        assertFalse(source.stats.any { "secret" in it || it == "other.bean" })
        assertEquals(listOf("glob"), source.listed)
    }

    @Test
    fun `a ledger with too many links is cut short and says so`() {
        val includes = (1..20).joinToString("") { "include \"f$it\"\n" }
        val source = MapLinkedSource("", "hmw" to includes, *(1..20).map { "f$it" to "" }.toTypedArray())

        val ledger = LedgerLinks(source, maxFiles = 5).walk("hmw")

        assertEquals(5, ledger.files.size)
        assertTrue(ledger.truncated)
    }

    @Test
    fun `a main file that is missing is reported`() {
        val (ledger, _) = walk("other" to "")

        assertTrue(ledger.files.isEmpty())
        assertEquals(listOf(LinkProblem.NOT_FOUND), ledger.problems())
    }
}
