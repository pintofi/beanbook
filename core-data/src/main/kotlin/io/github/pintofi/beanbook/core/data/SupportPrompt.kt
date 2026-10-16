package io.github.pintofi.beanbook.core.data

import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * When to mention, once, that the project accepts donations.
 *
 * Beanbook is free: no trial, no tiers, no keys. The only thing it asks is nothing, and after a
 * week of use it says -- once -- that people who find it useful can support it. A week because
 * by then a person knows whether the app is worth anything to them, and once because a request
 * repeated is a nag, which docs/ux.md §10 rules out.
 *
 * Kept in the app's state folder, outside every project, so it is never exported or backed up
 * with a ledger. Two lines: when the app was first seen, and whether the notice was answered.
 * A file that cannot be read counts as a first launch now, which can only delay the notice.
 */
class SupportPrompt(private val stateDir: File, private val after: Duration = Duration.ofDays(7)) {

    private val file get() = File(stateDir, "support-prompt")

    /** Records the first launch; later calls change nothing. */
    fun recordLaunch(now: Instant) {
        if (read() != null) return
        write(State(firstSeen = now, answered = false))
    }

    /** Whether the notice should be shown at [now]. */
    fun isDue(now: Instant): Boolean {
        val state = read() ?: return false
        return !state.answered && !now.isBefore(state.firstSeen.plus(after))
    }

    /** The person closed the notice or followed it. It is not shown again. */
    fun answer() {
        val state = read() ?: State(Instant.EPOCH, answered = true)
        write(state.copy(answered = true))
    }

    private data class State(val firstSeen: Instant, val answered: Boolean)

    private fun read(): State? {
        val lines = runCatching { file.takeIf { it.isFile }?.readLines() }.getOrNull() ?: return null
        val first = lines.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
        return State(Instant.ofEpochSecond(first), lines.getOrNull(1)?.trim() == "answered")
    }

    private fun write(state: State) {
        stateDir.mkdirs()
        val tmp = File(stateDir, "support-prompt.tmp")
        tmp.writeText("${state.firstSeen.epochSecond}\n${if (state.answered) "answered" else "open"}\n")
        check(tmp.renameTo(file)) { "could not record the support notice state" }
    }
}
