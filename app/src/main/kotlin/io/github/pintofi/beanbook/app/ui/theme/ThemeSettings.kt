package io.github.pintofi.beanbook.app.ui.theme

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the user wants the app to look, remembered between launches.
 *
 * A plain file rather than DataStore or SharedPreferences: it is a few small values, it is read
 * once at startup on the main thread before anything is drawn, and adding an asynchronous
 * store for that would mean a frame of the wrong theme while it loaded.
 *
 * Kept outside the project tree, like the rest of the app's own state — a display preference belongs to
 * the device, not to a ledger, and must not travel in a backup or a mirror.
 *
 * One `key=value` line each, so a value can be added without moving the others. The first version
 * was two bare lines (the theme, then dynamic colour) and is still read.
 */
class ThemeSettings(stateDir: File) {

    private val file = File(stateDir, "appearance")

    private val _choice = MutableStateFlow(ThemeChoice.SYSTEM)
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()

    private val _dynamicColour = MutableStateFlow(true)
    val dynamicColour: StateFlow<Boolean> = _dynamicColour.asStateFlow()

    private val _accent = MutableStateFlow(AccentPalette.TEAL)

    /** The accent used when the wallpaper is not supplying one and a project has none of its own. */
    val accent: StateFlow<AccentPalette> = _accent.asStateFlow()

    private val _projectAccents = MutableStateFlow<Map<String, AccentPalette>>(emptyMap())

    /** An accent a project has chosen for itself, by project id. It wins over the wallpaper and the app's. */
    val projectAccents: StateFlow<Map<String, AccentPalette>> = _projectAccents.asStateFlow()

    private val _comfort = MutableStateFlow(Comfort())

    /** The reading-comfort options: contrast, figure size, row density, motion, and how changes are marked. */
    val comfort: StateFlow<Comfort> = _comfort.asStateFlow()

    init {
        // An unreadable or hand-edited file falls back to the defaults rather than failing
        // to start: nothing here is worth refusing to launch over.
        runCatching {
            if (file.isFile) {
                val lines = file.readLines()
                if (lines.any { '=' in it }) readKeyed(lines) else readLegacy(lines)
            }
        }
    }

    private fun readLegacy(lines: List<String>) {
        _choice.value = lines.getOrNull(0)?.let(::choiceNamed) ?: ThemeChoice.SYSTEM
        _dynamicColour.value = lines.getOrNull(1)?.toBooleanStrictOrNull() ?: true
    }

    private fun readKeyed(lines: List<String>) {
        val projects = mutableMapOf<String, AccentPalette>()
        lines.forEach { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            when {
                key == "theme" -> _choice.value = choiceNamed(value) ?: ThemeChoice.SYSTEM
                key == "dynamic" -> _dynamicColour.value = value.toBooleanStrictOrNull() ?: true
                key == "contrast" -> _comfort.value = _comfort.value.copy(highContrast = value == "high")
                key == "figures" -> _comfort.value = _comfort.value.copy(largeFigures = value == "large")
                key == "rows" -> _comfort.value = _comfort.value.copy(compactRows = value == "compact")
                key == "motion" -> _comfort.value = _comfort.value.copy(reduceMotion = value == "reduced")
                key == "changes" ->
                    _comfort.value = _comfort.value.copy(changeMarks = ChangeMarks.named(value) ?: ChangeMarks.SUBTLE)
                key == "accent" -> _accent.value = AccentPalette.named(value) ?: AccentPalette.TEAL
                key.startsWith(PROJECT_PREFIX) ->
                    AccentPalette.named(value)?.let { projects[key.removePrefix(PROJECT_PREFIX)] = it }
            }
        }
        _projectAccents.value = projects
    }

    private fun choiceNamed(name: String) = ThemeChoice.entries.firstOrNull { it.name == name }

    fun setChoice(choice: ThemeChoice) {
        _choice.value = choice
        write()
    }

    fun setDynamicColour(enabled: Boolean) {
        _dynamicColour.value = enabled
        write()
    }

    fun setAccent(palette: AccentPalette) {
        _accent.value = palette
        write()
    }

    fun setComfort(comfort: Comfort) {
        _comfort.value = comfort
        write()
    }

    /** Gives [projectId] its own accent, or takes it back to the app's when [palette] is null. */
    fun setProjectAccent(projectId: String, palette: AccentPalette?) {
        _projectAccents.value = _projectAccents.value.toMutableMap().apply {
            if (palette == null) remove(projectId) else put(projectId, palette)
        }
        write()
    }

    private fun write() {
        runCatching {
            file.parentFile?.mkdirs()
            val text = buildString {
                appendLine("theme=${_choice.value.name}")
                appendLine("dynamic=${_dynamicColour.value}")
                appendLine("accent=${_accent.value.name}")
                val comfort = _comfort.value
                appendLine("contrast=${if (comfort.highContrast) "high" else "normal"}")
                appendLine("figures=${if (comfort.largeFigures) "large" else "normal"}")
                appendLine("rows=${if (comfort.compactRows) "compact" else "comfortable"}")
                appendLine("motion=${if (comfort.reduceMotion) "reduced" else "system"}")
                appendLine("changes=${comfort.changeMarks.name}")
                _projectAccents.value.toSortedMap().forEach { (id, palette) ->
                    appendLine("$PROJECT_PREFIX$id=${palette.name}")
                }
            }
            file.writeText(text)
        }
    }

    private companion object {
        const val PROJECT_PREFIX = "project."
    }
}
