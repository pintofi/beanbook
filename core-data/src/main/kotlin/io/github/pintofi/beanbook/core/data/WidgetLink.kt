package io.github.pintofi.beanbook.core.data

/**
 * Where a widget tap is allowed to send the app.
 *
 * A widget is a long-lived thing on someone's home screen, and the intent it carries was
 * built whenever it was last drawn — possibly before the project it names was deleted,
 * renamed, or replaced by a different one. So the id in the intent is a *request*, never an
 * instruction, and this decides whether to honour it (docs/ux.md §8).
 *
 * Pure Kotlin so the rule is unit-testable without a device: the interesting cases are all
 * about what a string is allowed to be, and none of them need Android to be true.
 */
object WidgetLink {

    /** Surfaces a widget may ask for. Anything else is ignored. */
    private val ALLOWED_SURFACES = setOf("ADD", "BALANCES", "JOURNAL")

    data class Target(val projectId: String, val surface: String)

    /**
     * @param requestedProject what the widget's intent carried, if anything.
     * @param requestedSurface likewise.
     * @param knownProjects the projects that actually exist right now.
     * @param activeProject what is open, used when the widget names nothing usable.
     */
    fun resolve(
        requestedProject: String?,
        requestedSurface: String?,
        knownProjects: Collection<String>,
        activeProject: String?,
    ): Target? {
        // A project id is only honoured if it is one that exists. Otherwise a stale widget —
        // or anything that managed to send this intent — could name a directory and have the
        // app take its word for it.
        val project = requestedProject?.takeIf { it in knownProjects }
            ?: activeProject
            ?: return null

        val surface = requestedSurface
            ?.uppercase()
            ?.takeIf { it in ALLOWED_SURFACES }
            ?: return Target(project, "BALANCES")

        return Target(project, surface)
    }
}
