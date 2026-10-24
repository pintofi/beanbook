package io.github.pintofi.beanbook.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Something that is used one job at a time and must be released exactly once, without making
 * the caller who asks for the release wait.
 *
 * A `PdfRenderer` allows one open page and is not thread-safe, so renders take turns. Closing
 * it used to spin on the main thread until the render in flight finished, which for a big page
 * froze the screen for as long as the render took (#73). Now [close] marks the resource closed
 * and returns: if nothing is running it releases at once, and if a job is running the release
 * is queued behind it, on [scope], and happens the moment that job ends. A job that has not
 * started yet sees it is closed and does not run.
 */
internal class IdleRelease(
    private val scope: CoroutineScope,
    private val release: () -> Unit,
) {
    private val lock = Mutex()

    @Volatile
    private var closed = false
    private var released = false

    /** Runs [job] alone, or returns null without running it when this is closed. */
    suspend fun <T> use(job: suspend () -> T): T? = lock.withLock { if (closed) null else job() }

    /** Marks this closed and gets it released, never waiting for a job in flight. */
    fun close() {
        closed = true
        if (lock.tryLock()) {
            try {
                releaseOnce()
            } finally {
                lock.unlock()
            }
        } else {
            scope.launch { lock.withLock { releaseOnce() } }
        }
    }

    private fun releaseOnce() {
        if (released) return
        released = true
        release()
    }
}
