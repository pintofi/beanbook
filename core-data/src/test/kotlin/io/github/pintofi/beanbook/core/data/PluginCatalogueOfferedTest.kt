package io.github.pintofi.beanbook.core.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app does not offer a plugin the compatibility run found cannot work on a phone. */
class PluginCatalogueOfferedTest {

    private val catalogue = PluginCatalogue.parse(File("../catalog/plugins.json").readText())

    @Test fun `no unsupported entry is offered`() {
        assertTrue(catalogue.entries.any { it.status == PluginCatalogue.UNSUPPORTED }, "the file records them")
        assertTrue(catalogue.offered().entries.none { it.status == PluginCatalogue.UNSUPPORTED })
    }

    @Test fun `everything else is offered, in the same order`() {
        val kept = catalogue.entries.filterNot { it.status == PluginCatalogue.UNSUPPORTED }
        assertEquals(kept.map { it.id }, catalogue.offered().entries.map { it.id })
    }
}
