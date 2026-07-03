package dev.stele.eval

import dev.stele.core.embed.Embedder
import dev.stele.core.embed.conceptCard
import dev.stele.core.embed.cosine
import dev.stele.core.embed.sectionCard
import dev.stele.core.model.Concept
import dev.stele.core.store.GraphStore

/**
 * The arm under test. Resolves domain concept(s) from a natural-language question,
 * then assembles the same context slice the MCP `concept_context` tool serves
 * (definition, rules, related, docs, implementing code).
 *
 * Two resolvers, A/B-tested as separate arms:
 *  - `stele` (embedder = null) — the CURRENT lexical resolver: per-keyword substring
 *    tally. Suffers from alias bloat (a concept whose doc-heading aliases contain half
 *    the product vocabulary outranks the right one on common words).
 *  - `stele-sem` — Phase-1 semantic resolution: each resolved concept gets a card
 *    (name + definition + aliases) embedded once; the question is embedded and ranked
 *    by cosine. L2 normalisation self-corrects alias bloat: a bloated card dilutes
 *    every word's weight, so distinctive concepts win.
 */
class SteleArm(
    private val store: GraphStore,
    private val topConcepts: Int = 2,
    private val embedder: Embedder? = null,
    override val name: String = if (embedder == null) "stele" else "stele-sem",
) : RetrievalArm {

    override fun retrieve(question: String): Retrieved {
        val concepts = if (embedder == null) resolve(question) else resolveSemantic(question)
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

                // Doc section BODIES, not just titles: a one-shot answerer can't drill
                // into a pointer — pointers-only context loses to raw-chunk RAG on
                // answer quality even when its recall is far higher (measured).
                // Question-aware drill: the ontology narrows to the right concept, but a
                // concept can own 100+ sections — serve the ones nearest to the QUESTION
                // (vector RAG's one real advantage, applied inside the graph neighbourhood).
                // Content DENSITY matters as much as content choice (measured: at 700-char
                // bodies the arm under-spent its token budget and lost the answer axis to
                // raw chunks despite 3× the recall). Spend the same budget the baseline gets.
                val docs = rankForQuestion(question, store.describingDocs(c.id))
                if (docs.isNotEmpty()) {
                    append("docs:\n")
                    for (d in docs.take(6)) {
                        append("  ## ${d.title} (${d.ref})\n")
                        d.body?.takeIf { it.isNotBlank() }?.let { append("  ${it.take(1400).trim()}\n") }
                        refs += d.ref
                    }
                }

                // Top implementing files by symbol count — a compact code map, not a dump.
                val impls = store.implementersOf(c.id)
                val byFile = impls.groupBy { it.ref.substringBefore('#') }
                append("implemented by ${impls.size} symbols across ${byFile.size} files, main ones:\n")
                for ((file, syms) in byFile.entries.sortedByDescending { it.value.size }.take(6)) {
                    append("  $file: ${syms.take(8).joinToString(", ") { it.title ?: it.ref }}\n")
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

    // The arm now measures the PRODUCT path: stored vectors written by `stele embed`
    // (what MCP serving uses). On-the-fly embedding remains as a fallback so the eval
    // still runs on a graph where `stele embed` hasn't been run yet.
    private val hasStoredConcepts by lazy { store.embeddedIds("concept_vectors", embedder!!.name).isNotEmpty() }
    private val hasStoredSections by lazy { store.embeddedIds("section_vectors", embedder!!.name).isNotEmpty() }

    /** Ranks a concept's doc sections by cosine to the question. */
    private fun rankForQuestion(question: String, docs: List<dev.stele.core.model.Artifact>): List<dev.stele.core.model.Artifact> {
        val emb = embedder ?: return docs
        if (docs.size <= 4) return docs
        val q = emb.embedQuery(question)
        if (hasStoredSections) return store.rankDocsForQuery(docs, q, emb.name)
        return docs.take(40)
            .map { d -> d to cosine(q, sectionVecs.getOrPut(d.id) { emb.embed(sectionCard(d)) }) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private val sectionVecs = HashMap<String, FloatArray>()

    /** Concept cards embedded once, lazily — resolved concepts only (same bar as serving). */
    private val cards: List<Pair<Concept, FloatArray>> by lazy {
        store.resolvedConcepts().map { c -> c to embedder!!.embed(conceptCard(c)) }
    }

    private fun resolveSemantic(question: String): List<Concept> {
        val exact = store.resolveConcept(question)
        val q = embedder!!.embedQuery(question)
        val ranked = if (hasStoredConcepts) {
            store.resolveSemanticConcepts(q, embedder.name, topK = topConcepts + 1)
        } else {
            cards.map { (c, v) -> c to cosine(q, v) }
                .filter { it.second > 0.05f }
                .sortedByDescending { it.second }
                .map { it.first }
        }
        return (listOfNotNull(exact) + ranked).distinctBy { it.id }.take(topConcepts)
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
