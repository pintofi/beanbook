package io.github.pintofi.beanbook.engine.local;

/**
 * The app's side of a save made in Fava's file editor.
 *
 * The engine process never writes to a project, so when the editor saves it hands the new content
 * to the app, which writes it through the write protocol and answers. The content is in a file in
 * the app's cache folder and only its path crosses Binder.
 */
interface IFavaWriteSink {

    /**
     * @param filePath the project file being written.
     * @param contentPath a file holding the bytes to write.
     * @param expectedSha the SHA-256 (hex) of the project file's bytes as the engine last read them.
     * @param operation what the write is, such as "editor-save"; the app decides how it is written.
     * @param viaShare whether the request came over Share on Wi-Fi.
     * @return "ok", or "error: " and a sentence the editor shows the person.
     */
    String commit(String filePath, String contentPath, String expectedSha, String operation, boolean viaShare);
}
