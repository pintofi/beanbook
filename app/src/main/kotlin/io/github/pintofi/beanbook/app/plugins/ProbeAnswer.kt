package io.github.pintofi.beanbook.app.plugins

/**
 * Why a probe of an updated plugin or package failed, or null when it imported.
 *
 * The engine answers with the Python dict `load_plugins` returns, as text: `'failed': 0` when every
 * module imported, and an `'error': '…'` for the first that did not. A missing answer is a failure:
 * an update that could not be tried is not kept.
 */
internal fun probeFailure(answer: String?): String? {
    if (answer == null) return "it could not be tried"
    if (Regex("'failed': 0\\b").containsMatchIn(answer)) return null
    return Regex("'error': ['\"](.{1,200}?)['\"],").find(answer)?.groupValues?.get(1) ?: "it did not import"
}
