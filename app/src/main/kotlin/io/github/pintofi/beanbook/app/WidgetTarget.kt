package io.github.pintofi.beanbook.app

/**
 * The project a widget asked to open, if it may be honoured.
 *
 * A widget is long-lived and the intent it carries was built whenever it was last drawn,
 * possibly before the project it names was deleted. `WidgetLink` checks the id against
 * the projects that exist rather than taking the intent's word for it.
 */
internal fun MainActivity.widgetTarget(
    app: BeancountApplication,
): io.github.pintofi.beanbook.core.data.WidgetLink.Target? {
    val requested = intent?.getStringExtra(
        io.github.pintofi.beanbook.app.widget.WidgetRenderer.EXTRA_PROJECT,
    ) ?: return null
    return io.github.pintofi.beanbook.core.data.WidgetLink.resolve(
        requestedProject = requested,
        requestedSurface = intent?.getStringExtra(
            io.github.pintofi.beanbook.app.widget.WidgetRenderer.EXTRA_SURFACE,
        ),
        knownProjects = app.projectsState.projects.value.map { it.id },
        activeProject = app.projectsState.activeId.value,
    )
}
