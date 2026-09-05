package io.github.pintofi.beanbook.app

import io.github.pintofi.beanbook.core.domain.PluginPolicy

/**
 * Sideloaded expert edition: arbitrary user Python, including packages with native code,
 * with the Java/Android bridge available.
 *
 * This is unsandboxed by design and documented as such (docs/threat-model.md 3.2). It is
 * never described as safe, and it is gated behind an explicit warning, per-project
 * enablement, provenance display, execution logs, and a plugin-free safe mode.
 */
object ExpertPluginPolicy : PluginPolicy {
    override val allowsUserCode = true
    override val allowsNativeCode = true
    override val allowsAndroidBridge = true
    override val allowsPackageDownload = true
    override val name = "expert"
}

internal fun currentPluginPolicy(): PluginPolicy = ExpertPluginPolicy
