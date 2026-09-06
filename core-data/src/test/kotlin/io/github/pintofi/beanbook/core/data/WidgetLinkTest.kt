package io.github.pintofi.beanbook.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a widget is allowed to ask the app to open.
 */
class WidgetLinkTest {

    private val projects = listOf("home", "business")

    @Test
    fun `a widget naming a real project opens it`() {
        val target = WidgetLink.resolve("business", "ADD", projects, activeProject = "home")

        assertEquals(WidgetLink.Target("business", "ADD"), target)
    }

    @Test
    fun `a widget naming a project that no longer exists falls back to the open one`() {
        // The ordinary case, not an attack: someone deletes a project and forgets the widget.
        val target = WidgetLink.resolve("deleted-last-week", "ADD", projects, activeProject = "home")

        assertEquals(WidgetLink.Target("home", "ADD"), target)
    }

    @Test
    fun `a project id is never taken on trust`() {
        listOf(
            "../../../data/data/io.github.pintofi.beanbook.app/files",
            "/etc/passwd",
            "home/../business",
            "",
        ).forEach { hostile ->
            val target = WidgetLink.resolve(hostile, "ADD", projects, activeProject = "home")
            assertEquals(
                expected = WidgetLink.Target("home", "ADD"),
                actual = target,
                message = "a path was accepted as a project id: $hostile",
            )
        }
    }

    @Test
    fun `an unknown surface falls back rather than being passed through`() {
        val target = WidgetLink.resolve("home", "DELETE_EVERYTHING", projects, "home")

        assertEquals(WidgetLink.Target("home", "BALANCES"), target)
    }

    @Test
    fun `a surface is matched without regard to case`() {
        assertEquals(
            WidgetLink.Target("home", "ADD"),
            WidgetLink.resolve("home", "add", projects, "home"),
        )
    }

    @Test
    fun `nothing is opened when there is no project at all`() {
        assertNull(WidgetLink.resolve("home", "ADD", knownProjects = emptyList(), activeProject = null))
    }

    /** Home is a surface a widget may ask for: net worth's own figure lives there (B54). */
    @Test fun homeIsAnAllowedSurface() {
        val target = WidgetLink.resolve(
            requestedProject = "home",
            requestedSurface = "HOME",
            knownProjects = listOf("home"),
            activeProject = "home",
        )
        assertEquals(WidgetLink.Target("home", "HOME"), target)
    }
}
