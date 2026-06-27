package dev.stele.core.store

private val STRING_LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

/** Decode a flat JSON string array (e.g. aliases_json) back to a list. */
internal fun parseStringArray(json: String): List<String> =
    STRING_LITERAL.findAll(json)
        .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
        .toList()

/** Minimal JSON encoder for the attrs/aliases/evidence columns (strings, numbers, lists, maps). */
internal fun jsonEncode(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""
    is Boolean, is Int, is Long, is Double, is Float -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
        jsonEncode(it.key.toString()) + ":" + jsonEncode(it.value)
    }
    is List<*> -> value.joinToString(",", "[", "]") { jsonEncode(it) }
    else -> jsonEncode(value.toString())
}
