package io.github.pintofi.beanbook.core.domain

/**
 * One account in the tree, with whatever hangs beneath it.
 *
 * [own] is null for an account that exists only as a path -- `Assets:Bank` in a ledger that
 * posts to `Assets:Bank:Checking` and nowhere else. Beancount reports balances per account
 * posted to, not rolled up, so those intermediate names have no figures of their own and are
 * still where a person expects to fold a branch away.
 */
data class AccountNode(
    val name: String,
    val own: AccountBalance?,
    val children: List<AccountNode>,
) {
    /** The last segment: "Assets:Bank:Checking" -> "Checking". */
    val leaf: String get() = name.substringAfterLast(':')

    val depth: Int get() = name.count { it == ':' }

    val hasChildren: Boolean get() = children.isNotEmpty()

    /**
     * This account and everything under it, added up by currency.
     *
     * The figure a folded branch has to show: collapsing `Expenses:Food` must not make the
     * money inside it disappear from the screen. Currencies are never mixed -- [
     * LedgerSummary.sumByCurrency] adds like to like and leaves the rest as separate figures.
     */
    fun subtotal(select: (AccountBalance) -> List<Amount>): List<Amount> =
        LedgerSummary.sumByCurrency(
            (own?.let(select).orEmpty()) + children.flatMap { it.subtotal(select) },
        )

    /** Every name in this subtree, this one included. */
    fun names(): List<String> = listOf(name) + children.flatMap { it.names() }
}

/** Builds the account tree Balances draws (B46). */
object AccountTree {

    /**
     * The children of [root], as a tree, from a flat list of per-account balances.
     *
     * Names are split on `:` and every missing ancestor is created, so a branch can be
     * folded at a level the ledger never posted to. Ordering is by name at every level,
     * which is how Beancount and Fava both list accounts.
     */
    fun of(accounts: List<AccountBalance>, root: String): List<AccountNode> {
        val under = accounts.filter { it.name == root || it.name.startsWith("$root:") }
        if (under.isEmpty()) return emptyList()
        val byName = under.associateBy { it.name }

        // Every path that must exist as a node, including ancestors nothing posted to.
        val paths = sortedSetOf<String>()
        under.forEach { account ->
            val segments = account.name.split(':')
            for (end in 1..segments.size) {
                paths += segments.subList(0, end).joinToString(":")
            }
        }

        fun build(prefix: String): List<AccountNode> = paths
            .filter { it.startsWith("$prefix:") && !it.removePrefix("$prefix:").contains(':') }
            .map { name -> AccountNode(name, byName[name], build(name)) }

        return build(root)
    }
}
