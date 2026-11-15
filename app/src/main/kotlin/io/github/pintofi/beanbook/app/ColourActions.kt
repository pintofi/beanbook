package io.github.pintofi.beanbook.app

import io.github.pintofi.beanbook.app.ui.theme.AccentPalette
import io.github.pintofi.beanbook.app.ui.theme.ProjectColour
import io.github.pintofi.beanbook.app.widget.WidgetRefresh

/**
 * Turns dynamic colour on or off for [projectId]. Turning it off leaves the project in the teal palette until
 * another is chosen; the widgets are redrawn, since they show the open project's colour.
 */
internal fun MainActivity.setDynamicColour(app: BeancountApplication, projectId: String?, on: Boolean) {
    projectId ?: return
    val settings = app.themeSettings
    settings.setProjectColour(
        projectId,
        if (on) ProjectColour.Dynamic else ProjectColour.Palette(AccentPalette.TEAL),
    )
    WidgetRefresh.refreshAll(this)
}
