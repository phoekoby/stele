package dev.stele.extractors

private val CAMEL = Regex("([a-z0-9])([A-Z])")
private val SEPARATORS = Regex("[_\\-./:]+")
private val WHITESPACE = Regex("\\s+")
private val DIGITS = Regex("^\\d+$")

/**
 * Split an identifier into normalized lowercase tokens — raw material for the
 * ubiquitous-language concept layer.
 *   validateRefund  -> ["validate", "refund"]
 *   REFUND_WINDOW    -> ["refund", "window"]
 *   node:path/util   -> ["node", "path", "util"]
 */
fun splitIdentifier(id: String): List<String> =
    id.replace(CAMEL, "$1 $2")        // camelCase boundary
        .replace(SEPARATORS, " ")     // snake / kebab / path / module separators
        .lowercase()
        .split(WHITESPACE)
        .filter { it.length > 1 && !DIGITS.matches(it) }

fun normalize(term: String): String = splitIdentifier(term).joinToString(" ")
