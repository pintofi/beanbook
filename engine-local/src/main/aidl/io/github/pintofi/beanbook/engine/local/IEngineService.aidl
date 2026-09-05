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

    /** Stops the server and joins its non-daemon threads. */
    String stopEngine();

    /** "python=3.13.1 beancount=3.2.3 fava=1.30.15 …", for manifests and diagnostics. */
    String engineVersions();

    /** Writes the ledger summary as JSON to outPath. Returns "ok" or "error: …". */
    /**
     * @param pluginDirs comma-separated directories to make importable while Beancount
     *   loads. Empty means no user plugins, which is also the fast path: the bridge guard
     *   is only installed when there is user code to guard.
     * @param allowBridge the edition's policy, decided on the app side.
     */
    String inspectToFile(String ledgerPath, int journalLimit, String outPath,
                         String pluginDirs, boolean allowBridge);

    /** Re-parses a project. Returns the validation JSON (bounded: 50 errors max). */
    /**
     * Imports user plugins. Runs here, not in the app process: plugin code is Python, and
     * Python lives on this side of the boundary (docs/architecture.md 7.1, risk R9).
     */
    String loadPlugins(String pluginDir, String modules, boolean allowBridge,
                       String journalPath, String journalLabel);

    /** Runs one user script with a deadline. [handle] is what cancelScript cancels. */
    String runScript(String scriptPath, String ledgerPath, double timeoutSeconds,
                     boolean allowBridge, String handle);

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
