package io.github.pintofi.beanbook.app.mirror

import org.junit.Test
import org.junit.Assert.assertEquals

class FileHashCacheTest {

    private val now = 1_000_000L
    private val old = now - 60_000

    private fun FileHashCache.read(key: String, size: Long, modified: Long, reads: IntArray, sha: String = "abc") =
        hashOf(key, size, modified, now) { reads[0]++; sha }

    @Test
    fun `a file with the same size and time is not read again`() {
        val cache = FileHashCache()
        val reads = intArrayOf(0)
        assertEquals("abc", cache.read("a", 10, old, reads))
        assertEquals("abc", cache.read("a", 10, old, reads))
        assertEquals(1, reads[0])
    }

    @Test
    fun `a changed size or time is read again`() {
        val cache = FileHashCache()
        val reads = intArrayOf(0)
        cache.read("a", 10, old, reads)
        assertEquals("def", cache.read("a", 11, old, reads, "def"))
        assertEquals("ghi", cache.read("a", 11, old + 1, reads, "ghi"))
        assertEquals(3, reads[0])
    }

    @Test
    fun `a provider that gives no time or no size is always read`() {
        val cache = FileHashCache()
        val reads = intArrayOf(0)
        repeat(2) { cache.read("a", 10, 0, reads) }
        repeat(2) { cache.read("b", -1, old, reads) }
        assertEquals(4, reads[0])
    }

    @Test
    fun `a file modified a moment ago is not trusted until it has settled`() {
        val cache = FileHashCache()
        val reads = intArrayOf(0)
        val justNow = now - 500
        repeat(2) { cache.read("a", 10, justNow, reads) }
        assertEquals(2, reads[0])
    }

    @Test
    fun `files that are gone are forgotten`() {
        val cache = FileHashCache()
        val reads = intArrayOf(0)
        cache.read("a", 10, old, reads)
        cache.retain(emptySet())
        cache.read("a", 10, old, reads)
        assertEquals(2, reads[0])
    }
}
