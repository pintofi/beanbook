package io.github.pintofi.beanbook.core.data

/**
 * A small JSON reader and writer, for the backup manifest and nothing else.
 *
 * Why not a library: `org.json` is Android-only and stubbed in JVM unit tests, and the two
 * obvious third-party choices are Apache-2.0 — a licence question this project takes
 * seriously (docs/architecture.md §7.1) — for a file we define ourselves and both write and
 * read. What is here covers exactly that file: objects, arrays, strings, numbers, booleans
 * and null, with no streaming, no reflection and no schema inference.
 *
 * The manifest is meant to be readable by a person with a text editor and by other tools, so
 * the output is real JSON with sorted keys and stable formatting, not a private encoding.
 */
sealed interface Json {

    data class Obj(val fields: Map<String, Json>) : Json {
        operator fun get(name: String): Json? = fields[name]
        fun string(name: String): String? = (fields[name] as? Str)?.value
        fun number(name: String): Double? = (fields[name] as? Num)?.value
        fun long(name: String): Long? = number(name)?.toLong()
        fun array(name: String): List<Json>? = (fields[name] as? Arr)?.items
        fun obj(name: String): Obj? = fields[name] as? Obj
    }

    data class Arr(val items: List<Json>) : Json

    data class Str(val value: String) : Json

    data class Num(val value: Double) : Json

    data class Bool(val value: Boolean) : Json

    data object Null : Json

    /** Pretty-printed with two-space indentation, because people read this file. */
    fun write(indent: String = ""): String = when (this) {
        is Str -> quote(value)
        is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Bool -> value.toString()
        Null -> "null"
        is Arr ->
            if (items.isEmpty()) {
                "[]"
            } else {
                items.joinToString(
                    separator = ",\n",
                    prefix = "[\n",
                    postfix = "\n$indent]",
                ) { "$indent  ${it.write("$indent  ")}" }
            }
        is Obj ->
            if (fields.isEmpty()) {
                "{}"
            } else {
                fields.entries.sortedBy { it.key }.joinToString(
                    separator = ",\n",
                    prefix = "{\n",
                    postfix = "\n$indent}",
                ) { (key, value) -> "$indent  ${quote(key)}: ${value.write("$indent  ")}" }
            }
    }

    companion object {
        fun of(value: String): Json = Str(value)
        fun of(value: Long): Json = Num(value.toDouble())
        fun of(value: Int): Json = Num(value.toDouble())
        fun of(value: Boolean): Json = Bool(value)
        fun obj(vararg fields: Pair<String, Json>): Obj = Obj(fields.toMap())
        fun arr(items: List<Json>): Arr = Arr(items)

        /** Parses [text], or returns null if it is not JSON this reader understands. */
        fun parse(text: String): Json? = runCatching {
            val reader = Reader(text)
            val value = reader.readValue()
            reader.skipWhitespace()
            if (!reader.atEnd) null else value
        }.getOrNull()

        internal fun quote(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
    }

    private class Reader(private val text: String) {
        private var index = 0

        val atEnd: Boolean get() = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Json {
            skipWhitespace()
            check(index < text.length) { "unexpected end of JSON" }
            return when (val char = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Str(readString())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else -> {
                    check(char == '-' || char.isDigit()) { "unexpected character '$char'" }
                    readNumber()
                }
            }
        }

        private fun readObject(): Obj {
            expect("{")
            val fields = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (peek() == '}') { index++; return Obj(fields) }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(":")
                val value = readValue()
                // A duplicated key would leave reader and writer disagreeing about which
                // value counts — the same ambiguity a signed payload must not have.
                check(fields.put(key, value) == null) { "duplicated key \"$key\"" }
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return Obj(fields) }
                    else -> error("expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(): Arr {
            expect("[")
            val items = mutableListOf<Json>()
            skipWhitespace()
            if (peek() == ']') { index++; return Arr(items) }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return Arr(items) }
                    else -> error("expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            expect("\"")
            val out = StringBuilder()
            while (index < text.length) {
                when (val char = text[index]) {
                    '"' -> { index++; return out.toString() }
                    '\\' -> {
                        index++
                        check(index < text.length) { "unterminated escape" }
                        when (val escaped = text[index]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'u' -> {
                                val hex = text.substring(index + 1, index + 5)
                                out.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> error("unknown escape \\$escaped")
                        }
                        index++
                    }
                    else -> { out.append(char); index++ }
                }
            }
            error("unterminated string")
        }

        private fun readNumber(): Num {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            return Num(text.substring(start, index).toDouble())
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun expect(literal: String) {
            check(text.startsWith(literal, index)) { "expected \"$literal\"" }
            index += literal.length
        }
    }
}
