package io.github.pintofi.beanbook.app.ui.theme

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the user wants the app to look, remembered between launches.
 *
 * A plain file rather than DataStore or SharedPreferences: it is two enum values, it is read
 * once at startup on the main thread before anything is drawn, and adding an asynchronous
 * store for that would mean a frame of the wrong theme while it loaded.
 *
 * Kept outside the project tree, like entitlement state — a display preference belongs to
 * the device, not to a ledger, and must not travel in a backup or a mirror.
 */
class ThemeSettings(stateDir: File) {

    private val file = File(stateDir, "appearance")

    private val _choice = MutableStateFlow(ThemeChoice.SYSTEM)
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()

    private val _dynamicColour = MutableStateFlow(true)
    val dynamicColour: StateFlow<Boolean> = _dynamicColour.asStateFlow()

    init {
        // An unreadable or hand-edited file falls back to the defaults rather than failing
        // to start: nothing here is worth refusing to launch over.
        runCatching {
            if (file.isFile) {
                val lines = file.readLines()
                _choice.value = lines.getOrNull(0)
                    ?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } }
                    ?: ThemeChoice.SYSTEM
                _dynamicColour.value = lines.getOrNull(1)?.toBooleanStrictOrNull() ?: true
            }
        }
    }

    fun setChoice(choice: ThemeChoice) {
        _choice.value = choice
        write()
    }

    fun setDynamicColour(enabled: Boolean) {
        _dynamicColour.value = enabled
        write()
    }

    private fun write() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("${_choice.value.name}\n${_dynamicColour.value}\n")
        }
    }
}
