package io.github.pintofi.beanbook.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A page named by a notification is used only when it is one the app has. */
class OpenPageTest {

    @Test fun `a surface is accepted by its name`() {
        assertEquals("JOURNAL", OpenPage.known("JOURNAL"))
        assertEquals("HOME", OpenPage.known("HOME"))
    }

    @Test fun `a section of More is accepted as MORE and its name`() {
        assertEquals("MORE:SHARING", OpenPage.known("MORE:SHARING"))
        assertEquals("MORE:PLUGINS", OpenPage.known(OpenPage.PLUGINS))
        assertEquals("MORE:SCRIPTS", OpenPage.known(OpenPage.SCRIPTS))
        assertEquals("MORE:DOCUMENTS", OpenPage.known(OpenPage.DOCUMENTS))
    }

    @Test fun `every page the app sends is one it knows`() {
        listOf(OpenPage.SHARING, OpenPage.PLUGINS, OpenPage.SCRIPTS, OpenPage.DOCUMENTS, OpenPage.JOURNAL)
            .forEach { assertEquals(it, OpenPage.known(it)) }
    }

    @Test fun `a serial after the hash is dropped from the page`() {
        assertEquals("MORE:SHARING", OpenPage.known("MORE:SHARING#7"))
        assertNull(OpenPage.known("NOWHERE#7"))
    }

    @Test fun `a page the app does not have is refused`() {
        assertNull(OpenPage.known("NOWHERE"))
        assertNull(OpenPage.known("MORE:NOWHERE"))
        assertNull(OpenPage.known("HOME:SHARING"))
        assertNull(OpenPage.known("../MORE"))
        assertNull(OpenPage.known(""))
        assertNull(OpenPage.known(null))
    }
}
