package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.BeancountText
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Changing a project's name, which is `option "title"` in its main file.
 *
 * The id and the folder do not change, so the mirror, the backups and the widgets that are keyed
 * by id are unaffected. Only the main file is edited, whatever it is called and wherever in the
 * project it sits; a title set in a file it includes is left alone.
 */
object ProjectRename {
    /** The longest name accepted. The Projects page and the widgets show it on one line. */
    const val MAX_TITLE_LENGTH = 100

    private val TITLE_OPTION = Regex(
        """^([ \t]*)option[ \t]+"title"[ \t]+"(?:[^"\\\r\n]|\\.)*"""",
        RegexOption.MULTILINE,
    )

    /** Why [title] cannot be used, or null when it can. */
    fun problemWith(title: String): String? = when {
        title.isBlank() -> "A name cannot be empty."
        title.any { it == '\n' || it == '\r' || it == '\u0000' } -> "A name must be on one line."
        title.trim().length > MAX_TITLE_LENGTH -> "A name can be at most $MAX_TITLE_LENGTH characters."
        else -> null
    }

    /**
     * [bytes] with [title] as the ledger's title, or a [Failure] saying why not.
     *
     * Every `option "title"` line is replaced, so the name Beancount uses (the last one) and the
     * name the Projects page shows (the first) agree. Whatever follows the option on its line, a
     * comment for example, stays. A ledger with no title gets one line at the very top, in the
     * file's own line ending. A header comment that no longer matches is left as it is. A file
     * that is not valid UTF-8 is refused, because rewriting it would change bytes it holds.
     */
    fun apply(bytes: ByteArray, title: String): Result<String> {
        val clean = title.trim()
        problemWith(clean)?.let { return Result.failure(Failure(it)) }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return Result.failure(Failure("The main file is not UTF-8 text, so the app will not rewrite it."))
        }
        val option = "option ${BeancountText.quote("title")} ${BeancountText.quote(clean)}"
        val replaced = TITLE_OPTION.replace(text) { it.groupValues[1] + option }
        if (replaced != text || TITLE_OPTION.containsMatchIn(text)) return Result.success(replaced)
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        val bom = if (text.startsWith("\uFEFF")) "\uFEFF" else ""
        return Result.success(bom + option + eol + text.removePrefix(bom))
    }

    class Failure(message: String) : Exception(message)
}
