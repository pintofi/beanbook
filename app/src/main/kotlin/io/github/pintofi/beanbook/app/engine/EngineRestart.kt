package io.github.pintofi.beanbook.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the engine is running older code than what is on disk (B49).
 *
 * Installing a plugin or fetching a package changes what the interpreter *would* import, and
 * changes nothing about what it already has: Python caches every module it has loaded, so a
 * package fetched after start is not importable in that process and an upgraded one keeps
 * its old code. The app used to say "reopen the ledger", which does not do it either --
 * reopening re-parses inside the same interpreter.
 *
 * So the state is tracked and the screen offers the only thing that actually works: end the
 * engine process and start a new one.
 */
class EngineRestartState {

    private val _needed = MutableStateFlow(false)
    val needed: StateFlow<Boolean> = _needed.asStateFlow()

    /** Something on disk changed under the running interpreter. */
    fun markNeeded() {
        _needed.value = true
    }

    /** A new engine process is running, so what it imported is what is on disk. */
    fun cleared() {
        _needed.value = false
    }
}
