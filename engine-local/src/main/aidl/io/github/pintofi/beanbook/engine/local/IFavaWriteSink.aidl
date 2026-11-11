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
     * @param filePath the project file the editor is saving.
     * @param contentPath a file holding the bytes to write.
     * @param expectedSha the SHA-256 (hex) of the project file's bytes as the engine last read them.
     * @return "ok", or "error: " and a sentence the editor shows the person.
     */
    String saveSource(String filePath, String contentPath, String expectedSha);
}
