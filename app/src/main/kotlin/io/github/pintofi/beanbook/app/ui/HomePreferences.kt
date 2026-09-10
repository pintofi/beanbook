package io.github.pintofi.beanbook.app.ui

import android.content.Context
import io.github.pintofi.beanbook.core.domain.HomeInsights
import io.github.pintofi.beanbook.core.domain.SpendingPeriod

/** One thing Home can show. The order of this list is the default order on screen (B71). */
enum class HomeCard(val label: String, val description: String) {
    FIGURES("Headline figures", "Net worth, income and expenses"),
    SPENDING("Where the money went", "By category, against the period before"),
    ATTENTION("Needs attention", "Errors, documents and the mirror, in one place"),
    PENDING("Uncleared and pending", "Everything the file marks with !"),
    RECENT("Recent entries", "The last few, as the Journal shows them"),
}

/**
 * What Home shows, in what order, and how the spending card is set up (B71).
 *
 * Customisable because the right home screen is not the same for everybody: somebody
 * reconciling a business wants what needs attention, somebody watching a budget wants the
 * categories, and the person who wanted the recent entries back can have them. Defaults are
 * the three cards that answer a question the Journal cannot, with Recent off -- it is the
 * Journal, shortened, which is what made the old screen redundant.
 *
 * A plain file beside the other display preferences: this belongs to the device, not to a
 * ledger, so it must not travel in an export, a backup or a mirror. Anything unreadable falls
 * back to the defaults rather than to an empty screen.
 */
class HomePreferences(private val context: Context) {

    private val file get() = context.filesDir.resolve("state/home-cards")

    data class Layout(
        val cards: List<HomeCard> = DEFAULT_CARDS,
        val period: SpendingPeriod = SpendingPeriod.THIS_MONTH,
        val categories: Int = HomeInsights.DEFAULT_CATEGORIES,
        val compare: Boolean = true,
    )

    fun read(): Layout = runCatching {
        val lines = file.readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val cards = lines.firstOrNull { it.startsWith("cards=") }
            ?.removePrefix("cards=")
            ?.split(',')
            ?.mapNotNull { name -> HomeCard.entries.firstOrNull { it.name == name } }
            .orEmpty()
        val period = lines.firstOrNull { it.startsWith("period=") }
            ?.removePrefix("period=")
            ?.let { name -> SpendingPeriod.entries.firstOrNull { it.name == name } }
        val categories = lines.firstOrNull { it.startsWith("categories=") }
            ?.removePrefix("categories=")?.toIntOrNull()
        val compare = lines.firstOrNull { it.startsWith("compare=") }?.removePrefix("compare=")
        Layout(
            // An empty list means every card was turned off, which is a choice; a file that
            // did not parse at all is not, and falls back to the defaults above.
            cards = if (lines.any { it.startsWith("cards=") }) cards else DEFAULT_CARDS,
            period = period ?: SpendingPeriod.THIS_MONTH,
            categories = categories?.coerceIn(MIN_CATEGORIES, MAX_CATEGORIES)
                ?: HomeInsights.DEFAULT_CATEGORIES,
            compare = compare != "false",
        )
    }.getOrDefault(Layout())

    fun write(layout: Layout) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                buildString {
                    append("cards=").append(layout.cards.joinToString(",") { it.name }).append('\n')
                    append("period=").append(layout.period.name).append('\n')
                    append("categories=").append(layout.categories).append('\n')
                    append("compare=").append(layout.compare).append('\n')
                },
            )
        }
    }

    companion object {
        val DEFAULT_CARDS = listOf(HomeCard.FIGURES, HomeCard.SPENDING, HomeCard.ATTENTION, HomeCard.PENDING)
        const val MIN_CATEGORIES = 3
        const val MAX_CATEGORIES = 12
    }
}
