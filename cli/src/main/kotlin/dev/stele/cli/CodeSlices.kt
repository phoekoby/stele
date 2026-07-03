package dev.stele.cli

import dev.stele.core.store.GraphStore
import java.io.File

/**
 * Builds the "CODE DOES" side of a context: the concept's implementing symbols
 * ranked by name-token overlap with the query (a rule sentence, a question),
 * with real bodies read from disk via the spans `ingest symbols` records.
 * Shared by `stele ask` and `stele drift`.
 */
object CodeSlices {

    data class Slice(val text: String, val files: List<String>)

    fun forConcept(
        store: GraphStore,
        conceptId: String,
        query: String,
        repoRoot: File?,
        maxFiles: Int = 6,
        maxBodies: Int = 4,
        maxBodyLines: Int = 40,
    ): Slice {
        val impls = store.implementersOf(conceptId)
        if (impls.isEmpty()) return Slice("", emptyList())
        val byFile = impls.groupBy { it.ref.substringBefore('#') }
        val qTokens = tokens(query)
        fun symScore(title: String?) = tokens(title ?: "").count { it in qTokens }

        val topFiles = byFile.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<dev.stele.core.model.Artifact>>> { e -> e.value.maxOf { symScore(it.title) } }
                    .thenByDescending { it.value.size },
            )
            .take(maxFiles)

        val files = mutableListOf<String>()
        val spans = store.symbolSpans(topFiles.take(3).flatMap { it.value }.map { it.id })
        var bodies = 0
        val text = buildString {
            for ((file, syms) in topFiles) {
                val ranked = syms.sortedByDescending { symScore(it.title) }
                append("  $file: ${ranked.take(10).joinToString(", ") { it.title ?: it.ref }}\n")
                files += file
                if (bodies >= maxBodies || repoRoot == null) continue
                val src = File(repoRoot, file).takeIf { it.isFile }?.let { runCatching { it.readLines() }.getOrNull() } ?: continue
                for (s in ranked) {
                    if (bodies >= maxBodies) break
                    val span = spans[s.id] ?: continue
                    val lines = src.subList((span.first - 1).coerceIn(0, src.size), span.last.coerceAtMost(src.size))
                    if (lines.isEmpty()) continue
                    append("    ─ ${s.title} ($file:${span.first}):\n")
                    for (line in lines.take(maxBodyLines)) append("      $line\n")
                    bodies++
                }
            }
        }
        return Slice(text, files)
    }

    /** camelCase/kebab/snake-aware lowercase tokens. */
    fun tokens(s: String): Set<String> =
        s.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()
}
