package io.github.pintofi.beanbook.core.domain

/** Why a typed account name cannot be used, each with the words to say it. */
sealed interface AccountProblem {
    fun describe(roots: AccountNames = AccountNames.DEFAULT): String

    data object UnknownRoot : AccountProblem {
        override fun describe(roots: AccountNames) =
            "Accounts start with ${roots.assets}, ${roots.liabilities}, ${roots.equity}, " +
                "${roots.income} or ${roots.expenses}."
    }

    data class NoName(val root: String) : AccountProblem {
        override fun describe(roots: AccountNames) =
            "Give it a name under $root, like $root:Coffee."
    }

    data class BadComponent(val text: String) : AccountProblem {
        override fun describe(roots: AccountNames) =
            "Each part starts with a capital letter or a number, and has only letters, numbers and " +
                "hyphens: Food:Coffee, not \"$text\"."
    }

    data object EmptyComponent : AccountProblem {
        override fun describe(roots: AccountNames) = "A part between two colons is empty."
    }

    data class Duplicate(val name: String) : AccountProblem {
        override fun describe(roots: AccountNames) = "$name is already on the list."
    }
}

/**
 * The rules for the name of an account, held in one place for setup, the Accounts screen and the
 * "open it" shortcut in the entry form.
 *
 * Beancount 3 allows Unicode letters and digits in the parts of a name, so "Expenses:Café" is
 * valid; the first part must be one of the five roots, under whatever names this ledger gives them.
 */
object AccountName {
    // Beancount's own rule for a name it will accept (ACC_COMP_NAME_RE in grammar.py, 3.2.3): the
    // first character after the root is an upper-case letter or a digit, the rest letters, digits
    // and hyphens. Narrower than what its lexer reads (BeancountText.ACCOUNT), because this rule
    // decides what the app writes. Combining marks are allowed so a decomposed "Ärzte" passes.
    private val FIRST = Regex("""[\p{Lu}\p{Nd}][\p{L}\p{M}\p{Nd}\-]*""")

    /** Everything wrong with [name], in the order a person would fix it. Empty when it is usable. */
    fun problems(
        name: String,
        roots: AccountNames = AccountNames.DEFAULT,
        existing: Collection<String> = emptyList(),
    ): List<AccountProblem> {
        val parts = name.split(':')
        val found = mutableListOf<AccountProblem>()
        if (parts.first() !in roots.roots) {
            found += AccountProblem.UnknownRoot
            return found
        }
        if (parts.size < 2) {
            found += AccountProblem.NoName(parts.first())
            return found
        }
        parts.drop(1).forEach { part ->
            when {
                part.isEmpty() -> found += AccountProblem.EmptyComponent
                !FIRST.matches(part) -> found += AccountProblem.BadComponent(part)
            }
        }
        if (found.isEmpty() && name in existing) found += AccountProblem.Duplicate(name)
        return found
    }

    /**
     * What somebody typed under a root, made into parts Beancount accepts: spaces become hyphens,
     * characters it does not allow are dropped, and each part starts with a capital. "food coffee"
     * is "Food-Coffee", and "food:coffee" is "Food:Coffee".
     */
    fun normalise(typed: String, locale: java.util.Locale = java.util.Locale.ROOT): String =
        java.text.Normalizer.normalize(typed, java.text.Normalizer.Form.NFC).split(':')
            .map { part ->
                part.trim().split(Regex("""\s+""")).joinToString("-") { word ->
                    word.filter { it.isLetterOrDigit() || it == '-' || it.isCombiningMark() }
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
                }.trim('-')
            }
            .filter { it.isNotEmpty() }
            .joinToString(":")
}

private fun Char.isCombiningMark() = Character.getType(this) == Character.NON_SPACING_MARK.toInt()
