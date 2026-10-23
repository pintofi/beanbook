package io.github.pintofi.beanbook.app.onboarding

/**
 * Says in plain words what Beancount objected to in the ledger the setup wrote.
 *
 * Beancount's own messages name a file and a line, and during setup the person cannot open the
 * files, so the position is dropped and the message is turned into what to change on which step.
 * A message not recognised is still shown, without its position.
 */
object SetupFailure {

    private val POSITION = Regex("""^\S+\.beancount:\d+\s+""")

    private val RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("""Invalid currency (\S+) for account '(.+?)'""") to {
            "The account ${it.groupValues[2]} does not accept ${it.groupValues[1]}. " +
                "On the Accounts step, add ${it.groupValues[1]} to its currencies, or turn off the switch that " +
                "makes every account use one currency."
        },
        Regex("""Invalid reference to unknown account '(.+?)'""") to {
            "Something refers to the account ${it.groupValues[1]}, which is not on your list. " +
                "Tick it on the Accounts step."
        },
        Regex("""Duplicate open directive for (.+)""") to {
            "The account ${it.groupValues[1].trim()} is on the list twice. Remove one of them on the Accounts step."
        },
        Regex("""Duplicate commodity directive for '?(.+?)'?$""") to {
            "The currency ${it.groupValues[1]} is declared twice. Remove the repeat on the Basics step."
        },
        Regex("""(?:Unknown currency|Missing Commodity directive for) '?(\S+?)'?$""") to {
            "The currency ${it.groupValues[1]} is used but not declared. Add it under Other currencies " +
                "on the Basics step."
        },
        Regex("""Non-leaf account '(.+?)' has postings""") to {
            "The account ${it.groupValues[1]} has accounts under it, so it cannot hold money itself. " +
                "Use one of the accounts under it, or turn off \"Post only to the last level\" on the Basics step."
        },
        Regex("""Balance failed for '(.+?)': expected (.+?) != accumulated (.+)""") to {
            "The opening balance for ${it.groupValues[1]} did not work out: it should be " +
                "${it.groupValues[2]} but Beancount counted ${it.groupValues[3]}. Check the amount you typed on " +
                "the Get set step."
        },
        Regex("""Transaction does not balance""") to {
            "An entry the setup wrote does not add up. Check the opening balances on the Get set step."
        },
    )

    /** One plain sentence for the first problem, and a count when there were more. */
    fun explain(errors: List<String>): String {
        if (errors.isEmpty()) return "Beancount could not read the new ledger."
        val first = describe(errors.first())
        val more = errors.size - 1
        return if (more > 0) "$first (And $more more.)" else first
    }

    private fun describe(raw: String): String {
        val message = raw.replace(POSITION, "").trim()
        for ((pattern, say) in RULES) pattern.find(message)?.let { return say(it) }
        return "Beancount reported: $message"
    }
}
