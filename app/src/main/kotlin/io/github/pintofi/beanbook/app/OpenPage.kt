package io.github.pintofi.beanbook.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The page of the app a notification opens when it is tapped.
 *
 * A page is a surface by name (`HOME`, `JOURNAL`, ...) or a section of More as `MORE:SECTION`
 * (`MORE:SHARING`). The names are checked against the app's own lists, never used as given:
 * [MainActivity] can be started by any app, so an intent naming a page it does not know opens
 * nothing special.
 */
internal object OpenPage {

    const val EXTRA = "io.github.pintofi.beanbook.OPEN_PAGE"

    /** Where Share on Wi-Fi, installs, scripts and filed documents are looked after. */
    const val SHARING = "MORE:SHARING"
    const val PLUGINS = "MORE:PLUGINS"
    const val SCRIPTS = "MORE:SCRIPTS"
    const val DOCUMENTS = "MORE:DOCUMENTS"
    const val JOURNAL = "JOURNAL"

    /** The page the intent asks for, if it names one the app has. */
    fun from(intent: Intent?): String? = known(intent?.getStringExtra(EXTRA))

    private var serial = 0

    /**
     * The page the intent asks for, as a request that is new every time: a notification tapped while the
     * app is open and already showing that page still counts as a request to go there. The serial after
     * the `#` is what makes it new; [known] ignores it.
     */
    fun request(intent: Intent?): String? = from(intent)?.let { "$it#${++serial}" }

    /** [raw] when it is a surface or a More section of this app, otherwise null. */
    fun known(raw: String?): String? {
        val name = raw?.substringBefore('#') ?: return null
        val surface = name.substringBefore(':')
        val section = name.substringAfter(':', "")
        val surfaceOk = Surface.entries.any { it.name == surface }
        return when {
            !surfaceOk -> null
            section.isEmpty() -> name
            surface == Surface.MORE.name && MoreSection.entries.any { it.name == section } -> name
            else -> null
        }
    }

    /**
     * A tap that opens the app on [page]. A running app is brought forward and told, rather than
     * a second copy being started on top of it, and each page has its own request so one
     * notification's destination is not swapped for another's.
     */
    fun pending(context: Context, page: String): PendingIntent = PendingIntent.getActivity(
        context,
        page.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA, page)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
