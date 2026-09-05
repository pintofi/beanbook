package dev.beancount.app

import dev.beancount.core.domain.PluginPolicy

/**
 * Google Play edition.
 *
 * User Python is allowed — the app never *downloads* code, it loads files the user
 * already has, which the Device and Network Abuse policy permits (docs/distribution.md
 * 2.2). The three restrictions below are Play-policy compliance, **not** a security
 * boundary: user code still runs with the app's privileges, and the UI must say so.
 */
object PlayPluginPolicy : PluginPolicy {
    override val allowsUserCode = true
    override val allowsNativeCode = false      // D1: no .so from a non-Play source
    override val allowsAndroidBridge = false   // D2: interpreter exception is about indirect access
    override val allowsPackageDownload = false // D1: no fetching code, native or not
    override val name = "play"
}

internal fun currentPluginPolicy(): PluginPolicy = PlayPluginPolicy
