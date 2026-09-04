package app.jabs.torboxdrop.data

/**
 * A deliberately small, dependency-free JSON representation used at the TorBox boundary.
 *
 * TorBox's published OpenAPI response schemas are intentionally loose, so generated DTOs are not
 * useful here. Keeping this parser pure Kotlin also makes the wire contract testable on the JVM.
 */
internal sealed interface JsonValue {
    data class Object(val values: LinkedHashMap<String, JsonValue> = linkedMapOf()) : JsonValue {
        operator fun get(name: String): JsonValue? = values[name]
        fun string(name: String): String? = values[name].asStringOrNull()
        fun long(name: String): Long? = values[name].asLongOrNull()
        fun int(name: String): Int? = values[name].asIntOrNull()
        fun double(name: String): Double? = values[name].asDoubleOrNull()
        fun boolean(name: String): Boolean? = values[name].asBooleanOrNull()
        fun array(name: String): Array? = values[name] as? Array
        fun obj(name: String): Object? = values[name] as? Object
    }

    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val raw: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal object Json {
    fun parse(source: String): JsonValue {
        val parser = Parser(source)
        return parser.parseValue().also {
            parser.skipWhitespace()
            if (!parser.isAtEnd()) throw JsonParseException("Unexpected trailing JSON content")
        }
    }

    fun stringify(value: JsonValue): String = buildString { appendValue(value) }

    fun obj(vararg values: Pair<String, JsonValue?>): JsonValue.Object = JsonValue.Object(
        linkedMapOf<String, JsonValue>().apply {
            values.forEach { (name, value) -> if (value != null) put(name, value) }
        },
    )

    fun string(value: String?): JsonValue? = value?.let(JsonValue::StringValue)
    fun number(value: Number?): JsonValue? = value?.let { JsonValue.NumberValue(it.toString()) }
    fun boolean(value: Boolean?): JsonValue? = value?.let(JsonValue::BooleanValue)
    fun strings(values: Collection<String>): JsonValue.Array =
        JsonValue.Array(values.map(JsonValue::StringValue))

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.Object -> {
                append('{')
                value.values.entries.forEachIndexed { index, (name, child) ->
                    if (index > 0) append(',')
                    appendQuoted(name)
                    append(':')
                    appendValue(child)
                }
                append('}')
            }
            is JsonValue.Array -> {
                append('[')
                value.values.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendValue(child)
                }
                append(']')
            }
            is JsonValue.StringValue -> appendQuoted(value.value)
            is JsonValue.NumberValue -> append(value.raw)
            is JsonValue.BooleanValue -> append(value.value)
            JsonValue.Null -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var position = 0

        fun isAtEnd(): Boolean = position >= source.length

        fun skipWhitespace() {
            while (!isAtEnd() && source[position].isWhitespace()) position++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (isAtEnd()) throw JsonParseException("Unexpected end of JSON")
            return when (source[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.StringValue(parseString())
                't' -> parseLiteral("true", JsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonParseException("Unexpected JSON token at position $position")
            }
        }

        private fun parseObject(): JsonValue.Object {
            expect('{')
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.Object(values)
            while (true) {
                skipWhitespace()
                if (isAtEnd() || source[position] != '"') {
                    throw JsonParseException("Expected object key at position $position")
                }
                val name = parseString()
                skipWhitespace()
                expect(':')
                values[name] = parseValue()
                skipWhitespace()
                if (consume('}')) break
                expect(',')
            }
            return JsonValue.Object(values)
        }

        private fun parseArray(): JsonValue.Array {
            expect('[')
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.Array(values)
            while (true) {
                values += parseValue()
                skipWhitespace()
                if (consume(']')) break
                expect(',')
            }
            return JsonValue.Array(values)
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (!isAtEnd()) {
                when (val character = source[position++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (isAtEnd()) throw JsonParseException("Unterminated JSON escape")
                        when (val escaped = source[position++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(parseUnicodeEscape())
                            else -> throw JsonParseException("Invalid JSON escape at position ${position - 1}")
                        }
                    }
                    else -> {
                        if (character.code < 0x20) throw JsonParseException("Control character in JSON string")
                        result.append(character)
                    }
                }
            }
            throw JsonParseException("Unterminated JSON string")
        }

        private fun parseUnicodeEscape(): Char {
            if (position + 4 > source.length) throw JsonParseException("Incomplete unicode escape")
            val raw = source.substring(position, position + 4)
            position += 4
            return raw.toIntOrNull(16)?.toChar() ?: throw JsonParseException("Invalid unicode escape")
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = position
            consume('-')
            if (consume('0')) {
                // A single leading zero is valid; a following fraction or exponent is handled below.
            } else {
                requireDigit()
                while (!isAtEnd() && source[position].isDigit()) position++
            }
            if (consume('.')) {
                requireDigit()
                while (!isAtEnd() && source[position].isDigit()) position++
            }
            if (!isAtEnd() && (source[position] == 'e' || source[position] == 'E')) {
                position++
                if (!isAtEnd() && (source[position] == '+' || source[position] == '-')) position++
                requireDigit()
                while (!isAtEnd() && source[position].isDigit()) position++
            }
            return JsonValue.NumberValue(source.substring(start, position))
        }

        private fun requireDigit() {
            if (isAtEnd() || !source[position].isDigit()) {
                throw JsonParseException("Expected digit at position $position")
            }
        }

        private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
            if (!source.regionMatches(position, literal, 0, literal.length)) {
                throw JsonParseException("Invalid JSON literal at position $position")
            }
            position += literal.length
            return value
        }

        private fun consume(expected: Char): Boolean {
            if (!isAtEnd() && source[position] == expected) {
                position++
                return true
            }
            return false
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) throw JsonParseException("Expected '$expected' at position $position")
        }
    }
}

internal class JsonParseException(message: String) : Exception(message)

internal fun JsonValue?.asStringOrNull(): String? = when (this) {
    is JsonValue.StringValue -> value
    is JsonValue.NumberValue -> raw
    is JsonValue.BooleanValue -> value.toString()
    else -> null
}?.takeUnless { it.equals("null", ignoreCase = true) }

internal fun JsonValue?.asLongOrNull(): Long? = when (this) {
    is JsonValue.NumberValue -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    is JsonValue.StringValue -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong()
    else -> null
}

internal fun JsonValue?.asIntOrNull(): Int? = asLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonValue?.asDoubleOrNull(): Double? = when (this) {
    is JsonValue.NumberValue -> raw.toDoubleOrNull()
    is JsonValue.StringValue -> value.trim().toDoubleOrNull()
    else -> null
}?.takeIf(Double::isFinite)

internal fun JsonValue?.asBooleanOrNull(): Boolean? = when (this) {
    is JsonValue.BooleanValue -> value
    is JsonValue.NumberValue -> when (raw.toDoubleOrNull()) {
        0.0 -> false
        1.0 -> true
        else -> null
    }
    is JsonValue.StringValue -> when (value.trim().lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
    else -> null
}
