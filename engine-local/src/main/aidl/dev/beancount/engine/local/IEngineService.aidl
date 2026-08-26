package dev.beancount.engine.local;

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
    String startEngine(String ledgerPath, String token);

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
    String validate(String mainFile);

    /** Locates an entry's source extent. Returns a small JSON object. */
    String entrySlice(String mainFile, String targetFile, int line);

    /**
     * Computes edited file content without writing to the project, and puts it at
     * outPath. op is "replace" or "delete".
     */
    String transformToFile(String op, String filePath, int fromLine, int toLine, String newText, String outPath);
}
