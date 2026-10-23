package io.github.pintofi.beanbook.app.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFailureTest {

    @Test fun `a currency an account does not accept names the account, the currency and the step`() {
        val text = SetupFailure.explain(
            listOf("accounts.beancount:12 Invalid currency EUR for account 'Assets:Cash'"),
        )
        assertTrue(text, "Assets:Cash" in text && "EUR" in text && "Accounts step" in text)
    }

    @Test fun `no file name or line number is shown`() {
        val text = SetupFailure.explain(listOf("main.beancount:44 Something Beancount has not got a rule for"))
        assertFalse(text, ".beancount" in text)
        assertFalse(text, ":44" in text)
        assertTrue(text, "Something Beancount has not got a rule for" in text)
    }

    @Test fun `an undeclared currency says where to declare it`() {
        val text = SetupFailure.explain(listOf("transactions.beancount:3 Unknown currency 'JPY'"))
        assertTrue(text, "JPY" in text && "Other currencies" in text)
    }

    @Test fun `further problems are counted`() {
        val text = SetupFailure.explain(List(3) { "a.beancount:1 Duplicate open directive for Assets:Cash" })
        assertTrue(text, "2 more" in text)
    }

    @Test fun `no problems still gives a sentence`() {
        assertTrue(SetupFailure.explain(emptyList()).isNotBlank())
    }
}
