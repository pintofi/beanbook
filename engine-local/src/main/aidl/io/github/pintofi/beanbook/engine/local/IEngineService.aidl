package io.github.pintofi.beanbook.engine.local;

/**
 * The arm's-length interface to the Beancount engine, which runs in its own process
 * (docs/milestones/m5.5-process-split-analysis.md).
 *
 * Every method returns a short string. Anything that could be large — a ledger summary,
 * or the content of an edited file — is written to a caller-supplied path in the app's
 * own cache directory and only the path crosses Binder, because a 7.7 MB ledger would
 * exceed the ~1 MB transaction limit.
 */
interface IEngineService {

    /** Starts Fava on loopback. Returns "port=<n> slug=<s> …". */
    /**
     * @param pluginDirs comma-separated directories to make importable while Fava is served.
     *   Without this a Fava extension installed through the app is invisible to Fava, which
     *   reports `No module named …` and 404s the extension's route — the parse path took
     *   plugin directories from the beginning and the serving path never did
     *   (docs/milestones/m14-part26.md).
     */
    String startEngine(String ledgerPath, String token, String pluginDirs);

    /** Stops the server and joins its non-daemon threads. Stops sharing too. */
    String stopEngine();

    /**
     * Serves the running Fava to other devices on the network, behind a sign-in (B93).
     *
     * @param host the device's own address on the Wi-Fi network; refused unless private.
     * @param passwordHash "pbkdf2_sha256$<iterations>$<salt hex>$<hash hex>". The password
     *   itself never crosses into the engine process.
     * @return "host=<h> port=<n>", or "error: …".
     */
    String startSharing(String host, int port, String username, String passwordHash);

    /** Stops sharing and signs every device out. */
    String stopSharing();

    /**
     * Stops the server and ends the engine process.
     *
     * Not `oneway`: a one-way call returns before the kill happens, and the caller then
     * binds the process that is still on its way out and is told the engine is already
     * running. Blocking means the transaction fails with a dead binder, which is the honest
     * signal that the process has gone.
     *
     * The point is the interpreter: Python caches every module it has imported, so a package
     * fetched after start is not importable in a process that has already looked for it, and
     * an upgraded one keeps its old code. Only a new process reads the new files. Nothing in
     * this process writes to a project, so there is nothing to lose by ending it.
     */
    void quitProcess();

    /** The engine process's pid, so a caller can tell a restarted process from the old one. */
    int processPid();

    /** "python=3.13.1 beancount=3.2.3 fava=1.30.15 …", for manifests and diagnostics. */
    String engineVersions();

    /** Writes the ledger summary as JSON to outPath. Returns "ok" or "error: …". */
    /**
     * @param pluginDirs comma-separated directories to make importable while Beancount
     *   loads. Empty means no user plugins, which is also the fast path: the bridge guard
     *   is only installed when there is user code to guard.
     * @param allowBridge the edition's policy, decided on the app side.
     */
    /**
     * @param dateFrom,dateTo an inclusive ISO date range, or empty for unbounded. Applied to
     *   the entries before anything is computed from them, as Fava's time filter is, so the
     *   balances and the journal describe the same period. Prices come from the whole file
     *   regardless: a rate published earlier still values a holding inside the range.
     */
    String inspectToFile(String ledgerPath, int journalLimit, String outPath,
                         String pluginDirs, boolean allowBridge,
                         String dateFrom, String dateTo);

    /** Re-parses a project. Returns the validation JSON (bounded: 50 errors max). */
    /**
     * Imports user plugins. Runs here, not in the app process: plugin code is Python, and
     * Python lives on this side of the boundary (docs/architecture.md 7.1, risk R9).
     */
    String loadPlugins(String pluginDir, String modules, boolean allowBridge,
                       String journalPath, String journalLabel);

    /** Runs one user script with a deadline. [handle] is what cancelScript cancels. */
    /**
     * @param pluginDirs the same directories a ledger load gets: the plugins the user turned
     *   on, and the packages they fetched. Without them a script can only import what the app
     *   bundles, which made fetching a module it asked for look like it had done nothing.
     */
    String runScript(String scriptPath, String ledgerPath, double timeoutSeconds,
                     boolean allowBridge, String handle, String pluginDirs);

    /**
     * Asks a running script to stop.
     *
     * A separate transaction on purpose: runScript occupies a binder thread for as long as
     * the script runs, and a cancel queued behind it would arrive after it had finished.
     */
    void cancelScript(String handle);

    String validate(String mainFile);

    /** Locates an entry's source extent. Returns a small JSON object. */
    String entrySlice(String mainFile, String targetFile, int line);

    /**
     * Computes edited file content without writing to the project, and puts it at
     * outPath. op is "replace" or "delete".
     */
    String transformToFile(String op, String filePath, int fromLine, int toLine, String newText, String outPath);
}
