package dev.beancount.core.domain

/**
 * What third-party Python may do in this build. The loader is shared by both flavors and
 * parameterized by this policy — the differences are Play-policy compliance, not a
 * security boundary, and the UI must never imply otherwise
 * (docs/distribution.md 2.1, docs/plugin-compatibility.md 6).
 */
interface PluginPolicy {
    /** Whether user-supplied Python may be loaded at all. */
    val allowsUserCode: Boolean

    /**
     * Whether packages containing native code (`.so`) may be installed.
     *
     * False for `play`: fetching native code from a non-Play source is prohibited by the
     * Device and Network Abuse policy.
     */
    val allowsNativeCode: Boolean

    /**
     * Whether user code may import the Java/Android bridge.
     *
     * False for `play`: the DNA policy's interpreter exception is written around
     * *indirect* access to Android APIs.
     */
    val allowsAndroidBridge: Boolean

    /**
     * Whether the app may **fetch** a Python package from the network on the user's request.
     *
     * False for `play`, for the same reason as [allowsNativeCode]: downloading executable
     * code from a non-Play source is what the Device and Network Abuse policy prohibits. It
     * is a separate capability from [allowsNativeCode] because the two differ — a pure-Python
     * wheel contains no `.so` and is still a download.
     */
    val allowsPackageDownload: Boolean

    val name: String
}
