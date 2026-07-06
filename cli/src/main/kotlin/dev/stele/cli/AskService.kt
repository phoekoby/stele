package dev.stele.cli

import dev.stele.cli.config.LlmConfig
import dev.stele.core.model.Concept
import dev.stele.core.store.GraphStore
import dev.stele.resolver.LlmClient
import java.io.File

/**
 * The two-sided grounded answer (DOCS SAY / CODE DOES / MISMATCH) — shared by
 * `stele ask` (CLI) and `stele serve` (HTTP). Resolution: exact name/alias, then
 * the stored semantic layer; docs are question-ranked; code bodies come via spans.
 */
object AskService {

    data class Result(val concepts: List<Concept>, val context: String, val answer: String?)

    fun ask(
        store: GraphStore,
        cfg: LlmConfig?,
        repoRoot: File?,
        question: String,
        top: Int = 2,
        llm: LlmClient? = null, // null → context only
    ): Result? {
        val embedder = EmbedderFactory.fromConfig(cfg)
        val qVec = runCatching { embedder.embedQuery(question) }.getOrNull()
        val concepts = buildList {
            store.resolveConcept(question)?.let { add(it) }
            if (qVec != null) addAll(store.resolveSemanticConcepts(qVec, embedder.name, topK = top + 1))
        }.distinctBy { it.id }.take(top)
        if (concepts.isEmpty()) return null

        val context = buildString {
            for (c in concepts) append(conceptSlice(store, c, question, qVec, embedder.name, repoRoot)).append('\n')
        }.trim()

        val answer = llm?.complete(SYSTEM, "Context:\n$context\n\nQuestion: $question")?.trim()
        return Result(concepts, context, answer)
    }

    /** One concept's two-sided slice: intent (docs/rules) + fact (code bodies). */
    private fun conceptSlice(store: GraphStore, c: Concept, question: String, qVec: FloatArray?, model: String, repoRoot: File?): String = buildString {
        append("concept: ${c.name}")
        c.boundedContext?.let { append("  [$it]") }
        append('\n')
        c.definition?.let { append(it).append('\n') }

        val rules = store.rulesFor(c.id)
        if (rules.isNotEmpty()) {
            append("\nPRODUCT RULES (must hold):\n")
            for (r in rules.take(8)) append("  ‣ ${r.title}\n")
        }

        val docs = store.describingDocs(c.id).let {
            if (qVec != null) store.rankDocsForQuery(it, qVec, model) else it
        }
        if (docs.isNotEmpty()) {
            append("\nDOCS SAY (intent):\n")
            for (d in docs.take(4)) {
                append("  ## ${d.title} (${d.ref})\n")
                d.body?.takeIf { it.isNotBlank() }?.let { append("  ${it.take(700).trim()}\n") }
            }
        }

        // Question-aware code drill: symbols ranked by name-token overlap with the
        // question, real bodies via spans (shared with `stele drift`).
        val slice = CodeSlices.forConcept(store, c.id, question, repoRoot)
        if (slice.text.isNotBlank()) {
            append("\nCODE DOES (fact):\n")
            append(slice.text)
        }
    }

    val SYSTEM = """
        You are a support engineer answering a product question for a colleague.
        The context has two sides: DOCS SAY (intent: docs + product rules) and CODE DOES (fact: real code).
        Answer ONLY from the context, concisely, in the language of the question:
        1) How it should work (per docs/rules) — cite the doc section.
        2) How it actually works (per code) — cite file names.
        3) MISMATCH: only if docs and code genuinely disagree, say so explicitly.
        If the context does not contain the answer, say exactly: I don't know from the available context.
    """.trimIndent()
}
