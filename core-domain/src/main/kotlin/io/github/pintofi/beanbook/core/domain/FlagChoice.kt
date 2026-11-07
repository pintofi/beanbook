package io.github.pintofi.beanbook.core.domain

/** Which kind of flag a transaction carries: `*`, `!`, or any other character Beancount allows. */
enum class FlagOption { CLEARED, PENDING, OTHER }

/**
 * The flag chosen on the entry form: Cleared (`*`), Pending (`!`), or an Other flag typed by the user.
 *
 * What was typed for Other is kept while another option is chosen, so going back finds it; only the
 * chosen option decides [flag].
 */
data class FlagChoice(val option: FlagOption = FlagOption.CLEARED, val otherText: String = "") {

    /** The flag as it is written in the ledger. */
    val flag: String
        get() = when (option) {
            FlagOption.CLEARED -> "*"
            FlagOption.PENDING -> "!"
            FlagOption.OTHER -> otherText.trim()
        }

    fun choose(next: FlagOption): FlagChoice = copy(option = next)

    fun withOther(text: String): FlagChoice = copy(option = FlagOption.OTHER, otherText = text)

    companion object {
        /**
         * The choice for a flag read from a file or a draft: none, `*` and `txn` are Cleared, `!` is
         * Pending, and anything else is an Other flag, shown as it is even if Beancount would refuse
         * it, so it is seen and not silently replaced.
         */
        fun of(flag: String?): FlagChoice = when (flag) {
            null, "", "*", "txn" -> FlagChoice()
            "!" -> FlagChoice(FlagOption.PENDING)
            else -> FlagChoice(FlagOption.OTHER, flag)
        }
    }
}
