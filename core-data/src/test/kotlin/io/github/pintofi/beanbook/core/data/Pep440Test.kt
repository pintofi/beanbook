package io.github.pintofi.beanbook.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Pep440Test {

    private fun v(text: String) = Pep440.parse(text)

    @Test
    fun `versions order by number, not by text`() {
        assertTrue(v("2.10") > v("2.9"))
        assertTrue(v("1.0.1") > v("1.0"))
        assertTrue(v("1!0.1") > v("5.0"))
    }

    @Test
    fun `trailing zeros do not matter`() {
        assertEquals(v("1.0"), v("1.0.0"))
        assertEquals(0, v("2").compareTo(v("2.0.0.0")))
    }

    @Test
    fun `the order of pre, final, post and dev releases`() {
        val ordered = listOf("1.0.dev1", "1.0a1", "1.0a2", "1.0b1", "1.0rc1", "1.0", "1.0.post1", "1.1.dev1", "1.1")
        ordered.zipWithNext().forEach { (older, newer) -> assertTrue(v(older) < v(newer), "$older < $newer") }
    }

    @Test
    fun `a dev release of a pre-release comes before it`() {
        assertTrue(v("1.0a1.dev1") < v("1.0a1"))
    }

    @Test
    fun `alternative spellings mean the same version`() {
        assertEquals(v("1.0rc1"), v("1.0c1"))
        assertEquals(v("1.0a1"), v("1.0alpha1"))
        assertEquals(v("1.0.post1"), v("1.0-1"))
        assertEquals(v("1.0.post1"), v("1.0rev1"))
        assertEquals(v("1.0"), v("v1.0"))
    }

    @Test
    fun `a local version is newer than its public one`() {
        assertTrue(v("1.0+abc") > v("1.0"))
        assertTrue(v("1.0+2") > v("1.0+abc"))
    }

    @Test
    fun `a pre-release is flagged and a commit or a tag is not a version`() {
        assertTrue(v("2.0rc1").isPreRelease)
        assertTrue(v("2.0.dev3").isPreRelease)
        assertFalse(v("2.0").isPreRelease)
        assertNull(Pep440.parseOrNull("3f2a9c1d4e5b6a7"))
        assertNull(Pep440.parseOrNull("v1-beta-x"))
        assertNotNull(Pep440.parseOrNull("v1.2"))
    }

    private fun spec(text: String) = PySpecifiers.parseOrNull(text)!!

    @Test
    fun `ranges`() {
        assertTrue(spec(">=2.1,<3").matches(v("2.5")))
        assertFalse(spec(">=2.1,<3").matches(v("3.0")))
        assertFalse(spec(">=2.1,<3").matches(v("2.0.9")))
        assertTrue(spec("").matches(v("9")))
    }

    @Test
    fun `equality and wildcards`() {
        assertTrue(spec("==1.4.*").matches(v("1.4.9")))
        assertFalse(spec("==1.4.*").matches(v("1.5")))
        assertTrue(spec("!=1.4.*").matches(v("1.5")))
        assertTrue(spec("==1.0").matches(v("1.0.0")))
        assertTrue(spec("==1.0").matches(v("1.0+local")))
    }

    @Test
    fun `compatible release`() {
        assertTrue(spec("~=1.4.2").matches(v("1.4.9")))
        assertFalse(spec("~=1.4.2").matches(v("1.5.0")))
        assertFalse(spec("~=1.4.2").matches(v("1.4.1")))
        assertTrue(spec("~=1.4").matches(v("1.9")))
        assertFalse(spec("~=1.4").matches(v("2.0")))
    }

    @Test
    fun `a pre-release only matches a range that names one`() {
        assertFalse(spec(">=1.0").matches(v("2.0rc1")))
        assertTrue(spec(">=1.0rc1").matches(v("1.0rc2")))
    }

    @Test
    fun `exclusive ordering ignores pre and post releases of the bound`() {
        assertFalse(spec("<2.0").matches(v("2.0rc1")))
        assertFalse(spec(">1.0").matches(v("1.0.post1")))
        assertTrue(spec(">1.0").matches(v("1.1")))
    }

    @Test
    fun `an operator that is not understood is not parsed`() {
        assertNull(PySpecifiers.parseOrNull("~~1.0"))
    }
}
