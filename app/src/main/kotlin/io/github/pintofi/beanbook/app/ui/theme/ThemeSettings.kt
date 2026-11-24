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
 * the device, not to a ledger. It travels in a backup only when the person ticks it there, never in a mirror.
 *
 * One `key=value` line each, so a value can be added without moving the others. The first version
 * was two bare lines (the theme, then dynamic colour) and is still read.
 */
class ThemeSettings(stateDir: File) {

    private val file = File(stateDir, "appearance")

    private val _choice = MutableStateFlow(ThemeChoice.SYSTEM)
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()

    /** What a project with no choice of its own gets: dynamic colour, unless an earlier version turned it off. */
    private var defaultColour: ProjectColour = ProjectColour.Dynamic

    private val _projectColours = MutableStateFlow<Map<String, ProjectColour>>(emptyMap())

    /** The colour each project chose for itself, by project id. */
    val projectColours: StateFlow<Map<String, ProjectColour>> = _projectColours.asStateFlow()

    /** The colour of [projectId], or the default one when it has not chosen (or there is no project). */
    fun colourOf(projectId: String?): ProjectColour = projectId?.let { _projectColours.value[it] } ?: defaultColour

    private val _comfort = MutableStateFlow(Comfort())

    /** The reading-comfort options: contrast, figure size, row density, motion, and how changes are marked. */
    val comfort: StateFlow<Comfort> = _comfort.asStateFlow()

    init {
        load()
    }

    /**
     * Reads the file again, for when something else has replaced it (a restored backup). Without it the next
     * change made here would write the old values back over the restored ones.
     */
    fun reload() {
        _choice.value = ThemeChoice.SYSTEM
        _comfort.value = Comfort()
        defaultColour = ProjectColour.Dynamic
        _projectColours.value = emptyMap()
        load()
    }

    private fun load() {
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
        defaultColour = if (lines.getOrNull(1)?.toBooleanStrictOrNull() == false) {
            ProjectColour.Palette(AccentPalette.TEAL)
        } else {
            ProjectColour.Dynamic
        }
    }

    private fun readKeyed(lines: List<String>) {
        val projects = mutableMapOf<String, ProjectColour>()
        var dynamic = true
        var accent = AccentPalette.TEAL
        lines.forEach { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            when {
                key == "theme" -> _choice.value = choiceNamed(value) ?: ThemeChoice.SYSTEM
                key == "dynamic" -> dynamic = value.toBooleanStrictOrNull() ?: true
                key == "contrast" -> _comfort.value = _comfort.value.copy(highContrast = value == "high")
                key == "figures" -> _comfort.value = _comfort.value.copy(largeFigures = value == "large")
                key == "rows" -> _comfort.value = _comfort.value.copy(compactRows = value == "compact")
                key == "motion" -> _comfort.value = _comfort.value.copy(reduceMotion = value == "reduced")
                key == "changes" ->
                    _comfort.value = _comfort.value.copy(changeMarks = ChangeMarks.named(value) ?: ChangeMarks.SUBTLE)
                key == "accent" -> accent = AccentPalette.named(value) ?: AccentPalette.TEAL
                key.startsWith(PROJECT_PREFIX) ->
                    colourNamed(value)?.let { projects[key.removePrefix(PROJECT_PREFIX)] = it }
            }
        }
        // The two app-wide lines an earlier version wrote now only say what a project with no choice gets.
        defaultColour = if (dynamic) ProjectColour.Dynamic else ProjectColour.Palette(accent)
        _projectColours.value = projects
    }

    private fun colourNamed(name: String): ProjectColour? =
        if (name == DYNAMIC) ProjectColour.Dynamic else AccentPalette.named(name)?.let { ProjectColour.Palette(it) }

    private fun choiceNamed(name: String) = ThemeChoice.entries.firstOrNull { it.name == name }

    fun setChoice(choice: ThemeChoice) {
        _choice.value = choice
        write()
    }

    fun setComfort(comfort: Comfort) {
        _comfort.value = comfort
        write()
    }

    /** Gives [projectId] its colour: the wallpaper's, or one of the palettes. */
    fun setProjectColour(projectId: String, colour: ProjectColour) {
        _projectColours.value = _projectColours.value + (projectId to colour)
        write()
    }

    private fun write() {
        runCatching {
            file.parentFile?.mkdirs()
            val text = buildString {
                appendLine("theme=${_choice.value.name}")
                // The default for a project with no line of its own, in the two keys earlier versions wrote.
                val default = defaultColour
                appendLine("dynamic=${default is ProjectColour.Dynamic}")
                appendLine("accent=${(default as? ProjectColour.Palette)?.palette?.name ?: AccentPalette.TEAL.name}")
                val comfort = _comfort.value
                appendLine("contrast=${if (comfort.highContrast) "high" else "normal"}")
                appendLine("figures=${if (comfort.largeFigures) "large" else "normal"}")
                appendLine("rows=${if (comfort.compactRows) "compact" else "comfortable"}")
                appendLine("motion=${if (comfort.reduceMotion) "reduced" else "system"}")
                appendLine("changes=${comfort.changeMarks.name}")
                _projectColours.value.toSortedMap().forEach { (id, colour) ->
                    val name = if (colour is ProjectColour.Palette) colour.palette.name else DYNAMIC
                    appendLine("$PROJECT_PREFIX$id=$name")
                }
            }
            file.writeText(text)
        }
    }

    private companion object {
        const val PROJECT_PREFIX = "project."
        const val DYNAMIC = "DYNAMIC"
    }
}
