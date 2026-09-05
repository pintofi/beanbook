package io.github.pintofi.beanbook.app.ui

import java.io.File

/**
 * Whether the app has introduced itself yet.
 *
 * A plain file in the device's state directory, following [io.github.pintofi.beanbook.app.ui.theme.ThemeSettings]:
 * it is one boolean, it is read once at startup before anything is drawn, and it belongs to
 * the device rather than to a ledger — so it must not travel in a backup or a mirror, or
 * restoring a backup would re-introduce the app to someone who has used it for a year.
 */
class FirstRun(stateDir: File) {

    private val file = File(stateDir, "welcomed")

    /** True until the welcome has been shown once and acknowledged. */
    var isFirstRun: Boolean = !file.isFile
        private set

    /**
     * Records that the welcome has been seen.
     *
     * A failure to write is swallowed deliberately: the cost is showing the welcome again on
     * the next launch, which is a great deal better than refusing to start over a file that
     * exists only to suppress a dialog.
     */
    fun markWelcomed() {
        isFirstRun = false
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("1")
        }
    }
}
