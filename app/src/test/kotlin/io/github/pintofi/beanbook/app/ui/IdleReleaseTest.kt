package io.github.pintofi.beanbook.app.ui

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing the PDF viewer used to spin on the main thread until the render in flight finished, so
 * leaving the screen froze it for as long as a large page took to draw (#73).
 */
class IdleReleaseTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val releases = AtomicInteger()
    private val resource = IdleRelease(scope) { releases.incrementAndGet() }

    private suspend fun awaitReleases(count: Int) = withTimeout(5_000) {
        while (releases.get() < count) delay(5)
    }

    @Test
    fun `closing an idle resource releases it at once`() {
        resource.close()

        assertEquals(1, releases.get())
    }

    @Test
    fun `closing while a job runs returns at once and releases when the job ends`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = scope.launch {
            resource.use {
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()

        val before = System.nanoTime()
        resource.close()
        val tookMillis = (System.nanoTime() - before) / 1_000_000

        assertTrue("close() waited for the job: $tookMillis ms", tookMillis < 200)
        assertEquals("released while the job was still running", 0, releases.get())

        finish.complete(Unit)
        job.join()
        awaitReleases(1)
        assertEquals(1, releases.get())
    }

    @Test
    fun `a job that has not started when it closes does not run`() = runBlocking {
        resource.close()

        val ran = resource.use { "ran" }

        assertNull(ran)
    }

    @Test
    fun `closing twice releases once`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = scope.launch { resource.use { started.complete(Unit); finish.await() } }
        started.await()

        resource.close()
        resource.close()
        finish.complete(Unit)
        job.join()
        awaitReleases(1)
        delay(100)

        assertEquals(1, releases.get())
    }

    @Test
    fun `jobs take turns`() = runBlocking {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val jobs = List(8) {
            scope.launch {
                resource.use {
                    most.updateAndGet { m -> maxOf(m, running.incrementAndGet()) }
                    delay(5)
                    running.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }

        assertEquals(1, most.get())
    }
}
