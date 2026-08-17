package dev.beancount.core.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * The single boundary between the app and whatever is serving the ledger: the embedded
 * CPython/Fava engine (local mode) or a self-hosted Fava (remote mode). Keeping this
 * interface free of Android and of Fava's URL scheme is what lets the two modes share UX
 * (docs/architecture.md 2).
 */
interface EngineClient {

    val status: StateFlow<EngineStatus>

    /** Starts the engine for [projectPath] and suspends until it is serving or failed. */
    suspend fun start(projectPath: String): EngineStatus

    /**
     * Stops the engine and releases its resources.
     *
     * Not optional: cheroot's worker threads are not daemon threads, so an engine that is
     * never stopped keeps the process alive (Phase 0 finding C7).
     */
    suspend fun stop()

    /**
     * Absolute URL for a Fava report path, including whatever authority and ledger slug
     * the current mode uses. Callers must never build engine URLs by hand.
     */
    fun urlFor(report: ReportPath): String

    /**
     * The origins the WebView is allowed to load. Anything else is blocked outright
     * (docs/threat-model.md 3.4).
     */
    fun allowedOrigins(): Set<String>
}

sealed interface EngineStatus {
    data object Stopped : EngineStatus
    data object Starting : EngineStatus

    /**
     * @param origin scheme://host:port actually bound — the port is ephemeral in local
     *   mode, so it is never assumed.
     * @param ledgerSlug Fava namespaces every report route by ledger slug, discovered at
     *   startup rather than guessed (Phase 0 finding, S4).
     */
    data class Serving(
        val origin: String,
        val ledgerSlug: String,
        val startupMillis: Long,
    ) : EngineStatus

    /** @param recoverable whether retrying could plausibly succeed without user action. */
    data class Failed(
        val message: String,
        val recoverable: Boolean,
        val cause: Throwable? = null,
    ) : EngineStatus
}

/** Report routes we address by name instead of by hand-built URL. */
enum class ReportPath(val path: String) {
    INCOME_STATEMENT("income_statement"),
    BALANCE_SHEET("balance_sheet"),
    TRIAL_BALANCE("trial_balance"),
    JOURNAL("journal"),
    QUERY("query"),
    HOLDINGS("holdings/by_account"),
    COMMODITIES("commodities"),
    EDITOR("editor"),
    ERRORS("errors"),
    ;
}

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

    /** Whether the app may download and install an APK update itself. Never on Play. */
    val allowsSelfUpdate: Boolean

    val name: String
}
