package io.github.pintofi.beanbook.app.ui

import io.github.pintofi.beanbook.core.domain.CapturedPage

/**
 * What the next photograph is for: another page of the batch, or a new photograph of one page
 * already in it (Retake).
 */
internal sealed interface ScanTarget {

    /** Add to the end of the batch, as the camera has always done. */
    data object Append : ScanTarget

    /** Replace the page at [index], if it is still the photograph [source]. */
    data class Replace(val index: Int, val source: String) : ScanTarget

    /** A form that survives the screen being recreated. */
    fun encode(): List<Any> = when (this) {
        Append -> listOf(APPEND)
        is Replace -> listOf(REPLACE, index, source)
    }

    /**
     * This target against the pages as they are now: a Retake whose page has moved or gone since it
     * was chosen falls back to adding, so the photograph is never lost.
     */
    fun checkedAgainst(pages: List<CapturedPage>): ScanTarget =
        if (this is Replace && pages.getOrNull(index)?.source != source) Append else this

    companion object {
        private const val APPEND = "append"
        private const val REPLACE = "replace"

        fun decode(saved: List<Any>): ScanTarget =
            if (saved.firstOrNull() == REPLACE && saved.size == REPLACE_SIZE) {
                Replace(saved[1] as Int, saved[2] as String)
            } else {
                Append
            }

        private const val REPLACE_SIZE = 3

        val Saver: androidx.compose.runtime.saveable.Saver<ScanTarget, Any> =
            androidx.compose.runtime.saveable.listSaver(
                save = { it.encode() },
                restore = { decode(it) },
            )
    }
}
