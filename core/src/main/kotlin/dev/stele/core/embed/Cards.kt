package dev.stele.core.embed

import dev.stele.core.model.Artifact
import dev.stele.core.model.Concept

/**
 * The text that represents a concept in vector space. The name is repeated to
 * outweigh any single alias; noisy aliases only dilute the card (L2 norm), they
 * can't hijack it the way they hijack lexical substring matching.
 */
fun conceptCard(c: Concept): String = buildString {
    repeat(3) { append(c.name).append(' ') }
    c.boundedContext?.let { append(it).append(' ') }
    c.definition?.let { append(it).append(' ') }
    append(c.aliases.joinToString(" "))
}

/** The text that represents a doc section in vector space (capped — chars ≠ tokens). */
fun sectionCard(a: Artifact): String =
    "${a.title ?: ""}\n${(a.body ?: "").take(1500)}"
