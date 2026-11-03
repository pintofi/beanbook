package io.github.pintofi.beanbook.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The accounts file is written to only when the ledger reads it. */
class AccountsLedgerTest {

    private val project = Files.createTempDirectory("accounts-ledger").toFile()
    private val target = File(project, ACCOUNTS_FILE)

    @Test fun `a file the engine read last time is included`() {
        target.parentFile.mkdirs()
        target.writeText(ACCOUNTS_FILE_HEADER)
        assertTrue(isIncluded(target, "files/accounts.beancount", listOf(target.path), mainText = ""))
    }

    @Test fun `a file the main file includes is included even if the engine has not read it yet`() {
        val main = "option \"title\" \"x\"\ninclude \"files/accounts.beancount\"\n"
        assertTrue(isIncluded(target, "files/accounts.beancount", emptyList(), main))
    }

    @Test fun `a file nothing includes is not`() {
        val main = "option \"title\" \"x\"\ninclude \"files/transactions.beancount\"\n"
        assertFalse(isIncluded(target, "files/accounts.beancount", emptyList(), main))
        assertFalse(isIncluded(target, "files/accounts.beancount", listOf("/elsewhere/main.beancount"), main))
    }

    @Test fun `a commented out include line does not count`() {
        val main = "; include \"files/accounts.beancount\"\n"
        assertFalse(isIncluded(target, "files/accounts.beancount", emptyList(), main))
    }

    @Test fun `a similar name is not the same file`() {
        val main = "include \"files/accounts.beancount.bak\"\ninclude \"old/files/accounts.beancount\"\n"
        assertFalse(isIncluded(target, "files/accounts.beancount", emptyList(), main))
    }
}
