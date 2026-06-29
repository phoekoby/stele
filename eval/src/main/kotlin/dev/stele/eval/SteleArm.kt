package dev.stele.eval

import dev.stele.core.model.Concept
import dev.stele.core.store.GraphStore

/**
 * The arm under test. Resolves domain concept(s) from a natural-language question
 * with the CURRENT lexical resolver, then assembles the same context slice the MCP
 * `concept_context` tool serves (definition, rules, related, docs, implementing code).
 *
 * Phase 1 (NL concept resolution via embeddings) is meant to replace [resolve] — this
 * arm is exactly where that win has to show up against the vector/agentic baselines.
 */
class SteleArm(
    private val store: GraphStore,
    private val topConcepts: Int = 2,
) : RetrievalArm {
    override val name = "stele"

    override fun retrieve(question: String): Retrieved {
        val concepts = resolve(question)
        if (concepts.isEmpty()) return Retrieved(emptyList(), "")
        val refs = mutableListOf<String>()
        val context = buildString {
            for (c in concepts) {
                append("concept: ${c.name}")
                c.boundedContext?.let { append("  [$it]") }
                append('\n')
                c.definition?.let { append(it).append('\n') }
                if (c.aliases.isNotEmpty()) append("aliases: ${c.aliases.joinToString(", ")}\n")

                val related = store.relatedConcepts(c.id)
                if (related.isNotEmpty()) append("related: ${related.take(10).joinToString(", ") { it.name }}\n")

                val rules = store.rulesFor(c.id)
                if (rules.isNotEmpty()) {
                    append("product rules:\n")
                    for (r in rules.take(8)) append("  - ${r.title}\n")
                }

                val docs = store.describingDocs(c.id)
                if (docs.isNotEmpty()) {
                    append("docs:\n")
                    for (d in docs.take(6)) {
                        append("  - ${d.title} (${d.ref})\n")
                        refs += d.ref
                    }
                }

                val impls = store.implementersOf(c.id)
                val byFile = impls.groupBy { it.ref.substringBefore('#') }
                append("implemented by ${impls.size} symbols across ${byFile.size} files:\n")
                for ((file, syms) in byFile.entries.sortedBy { it.key }) {
                    append("  $file: ${syms.joinToString(", ") { it.title ?: it.ref }}\n")
                    refs += file
                }
                append('\n')
            }
        }.trim()
        return Retrieved(concepts.map { it.name }, context, refs.distinct())
    }

    /**
     * Lexical resolution from a full question: exact phrase first, then per-keyword
     * substring search ranked by how many keywords hit each concept. Deliberately the
     * naive baseline — when this misses on conversational phrasing, that's the gap
     * Phase 1's embedding resolver must close.
     */
    private fun resolve(question: String): List<Concept> {
        store.resolveConcept(question)?.let { return listOf(it) }
        val words = TOKEN.findAll(question.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP }
            .toList()
        val tally = LinkedHashMap<String, Pair<Concept, Int>>()
        for (w in words) {
            for (c in store.searchConcepts(w, limit = 5)) {
                val cur = tally[c.id]
                tally[c.id] = (cur?.first ?: c) to ((cur?.second ?: 0) + 1)
            }
        }
        return tally.values.sortedByDescending { it.second }.take(topConcepts).map { it.first }
    }

    companion object {
        private val TOKEN = Regex("[a-z][a-z0-9]+")
        private val STOP = setOf(
            "the", "and", "for", "how", "why", "what", "where", "when", "does", "work", "works",
            "working", "implemented", "currently", "feature", "this", "that", "with", "from", "into",
            "about", "not", "explain", "show", "find", "please", "you", "your", "our", "its", "are", "can",
        )
    }
}
