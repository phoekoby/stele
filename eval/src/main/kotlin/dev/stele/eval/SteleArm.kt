package dev.stele.eval

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
                val docs = rankForQuestion(question, store.describingDocs(c.id))
                if (docs.isNotEmpty()) {
                    append("docs:\n")
                    for (d in docs.take(4)) {
                        append("  ## ${d.title} (${d.ref})\n")
                        d.body?.takeIf { it.isNotBlank() }?.let { append("  ${it.take(700).trim()}\n") }
                        refs += d.ref
                    }
                }

                // Top implementing files by symbol count — a compact code map, not a dump.
                val impls = store.implementersOf(c.id)
                val byFile = impls.groupBy { it.ref.substringBefore('#') }
                append("implemented by ${impls.size} symbols across ${byFile.size} files, main ones:\n")
                for ((file, syms) in byFile.entries.sortedByDescending { it.value.size }.take(10)) {
                    append("  $file: ${syms.take(12).joinToString(", ") { it.title ?: it.ref }}\n")
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

    /**
     * Ranks a concept's doc sections by cosine to the question (falls back to the
     * stored confidence order without an embedder). Section vectors are cached by
     * artifact id — a concept's sections are embedded once per arm lifetime.
     */
    private fun rankForQuestion(question: String, docs: List<dev.stele.core.model.Artifact>): List<dev.stele.core.model.Artifact> {
        val emb = embedder ?: return docs
        if (docs.size <= 4) return docs
        val q = emb.embedQuery(question)
        return docs.take(40)
            .map { d ->
                val v = sectionVecs.getOrPut(d.id) {
                    emb.embed("${d.title ?: ""}\n${(d.body ?: "").take(1500)}")
                }
                d to cosine(q, v)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private val sectionVecs = HashMap<String, FloatArray>()

    /** Concept cards embedded once, lazily — resolved concepts only (same bar as serving). */
    private val cards: List<Pair<Concept, FloatArray>> by lazy {
        store.searchConcepts("", limit = 10_000)
            .filter { it.definition != null }
            .map { c -> c to embedder!!.embed(card(c)) }
    }

    /** Name is repeated to outweigh any single alias; junk aliases only dilute the card. */
    private fun card(c: Concept): String = buildString {
        repeat(3) { append(c.name).append(' ') }
        c.boundedContext?.let { append(it).append(' ') }
        c.definition?.let { append(it).append(' ') }
        append(c.aliases.joinToString(" "))
    }

    private fun resolveSemantic(question: String): List<Concept> {
        val exact = store.resolveConcept(question)
        val q = embedder!!.embedQuery(question)
        val ranked = cards
            .map { (c, v) -> c to cosine(q, v) }
            .filter { it.second > 0.05f }
            .sortedByDescending { it.second }
            .map { it.first }
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
