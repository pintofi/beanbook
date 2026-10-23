package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.Cost
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.domain.TemplatePosting
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateStoreTest {

    private val root: File = Files.createTempDirectory("templates").toFile()
    private val workspace = ProjectWorkspace(root)
    private val store = TemplateStore(workspace)

    private val rent = EntryTemplate(
        name = "Rent",
        currency = "EUR",
        payee = "Landlord",
        narration = "Monthly rent",
        tags = listOf("home"),
        links = listOf("lease-2026"),
        postings = listOf(
            TemplatePosting("Expenses:Rent", BigDecimal("750.00")),
            TemplatePosting("Assets:Bank:Giro"),
        ),
    )

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    @Test
    fun `a template survives a restart, every field intact`() {
        store.save("p", rent)

        val restored = TemplateStore(workspace).load("p").single()

        assertEquals(rent, restored)
    }

    @Test
    fun `an elided posting stays elided across a save and load`() {
        store.save("p", rent)

        assertNull(
            store.load("p").single().postings.last().amount,
            "a template must not gain an amount by being stored",
        )
    }

    @Test
    fun `the amount is stored as text, so no precision is lost`() {
        store.save("p", rent.copy(postings = listOf(TemplatePosting("Expenses:Rent", BigDecimal("0.10")))))

        val amount = store.load("p").single().postings.single().amount
        assertEquals(BigDecimal("0.10"), amount)
        assertEquals("0.10", amount?.toPlainString(), "trailing zeroes carry the precision")
    }

    @Test
    fun `a payee containing a tab, a newline and a backslash round-trips`() {
        // The record separator is a tab, so a payee containing one would otherwise split the
        // record and corrupt every template after it.
        val awkward = rent.copy(payee = "Land\tlord\nand\\co", narration = "two\tcolumns")
        store.save("p", awkward)

        assertEquals(awkward, store.load("p").single())
    }

    @Test
    fun `saving the same name twice replaces rather than duplicates`() {
        store.save("p", rent)
        store.save("p", rent.copy(narration = "Rent, increased"))

        val templates = store.load("p")
        assertEquals(1, templates.size, "templates: $templates")
        assertEquals("Rent, increased", templates.single().narration)
    }

    @Test
    fun `templates are kept in the order they were saved`() {
        store.save("p", rent)
        store.save("p", rent.copy(name = "Groceries", payee = "Grocer"))
        store.save("p", rent.copy(name = "Salary", payee = "Employer"))

        assertEquals(listOf("Rent", "Groceries", "Salary"), store.load("p").map { it.name })
    }

    @Test
    fun `deleting removes one template and leaves the rest`() {
        store.save("p", rent)
        store.save("p", rent.copy(name = "Groceries"))

        store.delete("p", "Rent")

        assertEquals(listOf("Groceries"), store.load("p").map { it.name })
    }

    @Test
    fun `templates belong to one project and are not visible from another`() {
        store.save("p", rent)

        assertEquals(emptyList(), store.load("other"))
    }

    @Test
    fun `a file from a future version yields nothing and is never overwritten`() {
        val file = root.resolve("templates/p.templates")
        file.parentFile.mkdirs()
        val fromTheFuture = "v3\nsomething we do not understand\n"
        file.writeText(fromTheFuture)

        assertEquals(emptyList(), store.load("p"), "an unreadable file must not be guessed at")
        val error = runCatching { store.save("p", rent) }.exceptionOrNull()
        assertTrue(error is IllegalStateException, "expected a refusal, got: $error")
        assertEquals(fromTheFuture, file.readText(), "the unreadable file must be left alone")
    }

    @Test
    fun `an unknown record type inside a readable file is skipped, not fatal`() {
        store.save("p", rent)
        val file = root.resolve("templates/p.templates")
        file.writeText(file.readText() + "future-field\tsomething\n")

        assertEquals(rent, store.load("p").single())
    }

    @Test
    fun `a template of an exchange, a lot and a price survives a restart`() {
        val exchange = EntryTemplate(
            name = "Buy fund",
            currency = "USD",
            postings = listOf(
                TemplatePosting(
                    account = "Assets:Broker",
                    amount = BigDecimal("10"),
                    currency = "VTI",
                    cost = Cost(Amount(BigDecimal("212.50"), "USD"), date = "2026-09-01", label = "lot 1"),
                    price = Amount(BigDecimal("213"), "USD"),
                    details = "{212.50 USD}",
                    comment = "bought\tit",
                    metadata = mapOf("broker" to "x y"),
                ),
                TemplatePosting("Assets:Cash", BigDecimal("-2125.00")),
                TemplatePosting("Assets:Eur", BigDecimal("-90.00"), currency = "EUR"),
            ),
        )
        store.save("p", exchange)

        assertEquals(exchange, TemplateStore(workspace).load("p").single())
    }

    @Test
    fun `a template saved before postings had their own currency still loads, and is upgraded when saved again`() {
        val file = workspace.templatesDir.resolve("${workspace.projectDir("p").name}.templates")
        file.parentFile.mkdirs()
        file.writeText(
            "v1\ntemplate\tRent\nmeta\tEUR\t*\tLandlord\tMonthly rent\n" +
                "posting\tExpenses:Rent\t750.00\nposting\tAssets:Bank:Giro\t\n",
        )

        assertEquals(rent.copy(tags = emptyList(), links = emptyList()), store.load("p").single())

        store.save("p", EntryTemplate("Other", "EUR", listOf(TemplatePosting("Expenses:A", BigDecimal("1")))))

        assertEquals("v2", file.readLines().first())
        assertEquals(listOf("Rent", "Other"), store.load("p").map { it.name })
        assertEquals(rent.copy(tags = emptyList(), links = emptyList()), store.load("p").first())
    }
}
