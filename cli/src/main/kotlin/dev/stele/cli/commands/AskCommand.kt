package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.EmbedderFactory
import dev.stele.cli.LlmFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.model.Concept
import dev.stele.core.store.GraphStore
import dev.stele.core.store.UsageLog
import java.io.File

/**
 * The support/PM entry point: a natural-language question → a grounded, two-sided
 * answer. The graph resolves the concept and serves BOTH sides — what the docs/rules
 * say the product should do, and what the code actually does (symbol bodies via the
 * spans `ingest symbols` records) — so the model can answer *and* flag divergence.
 */
class AskCommand : CliktCommand(
    name = "ask",
    help = "Ask a product question — answered from docs (how it should work) + code (how it works)",
) {
    private val question by argument(name = "question", help = "A natural-language question")
    private val top by option("--top", help = "Concepts to include").int().default(2)
    private val contextOnly by option("--context-only", help = "Print the assembled context, skip the LLM").flag()
    private val provider by option("--provider", help = "LLM provider for the answer")
    private val model by option("--model")
    private val ollamaUrl by option("--ollama-url")

    override fun run() {
        val dbFile = requireDb()
        val conn = openDb(dbFile.path)
        val store = GraphStore(conn)
        val cfg = ConfigLoader.findAndLoad()?.llm
        val embedder = EmbedderFactory.fromConfig(cfg)
        val started = System.currentTimeMillis()

        // Resolve: exact name/alias, then semantic over the stored concept cards.
        val qVec = runCatching { embedder.embedQuery(question) }.getOrNull()
        val concepts = buildList {
            store.resolveConcept(question)?.let { add(it) }
            if (qVec != null) addAll(store.resolveSemanticConcepts(qVec, embedder.name, topK = top + 1))
        }.distinctBy { it.id }.take(top)

        if (concepts.isEmpty()) {
            echo("Couldn't resolve the question to a concept. Run `stele sync` (incl. `stele embed`) first, or rephrase.")
            conn.close()
            return
        }

        val repoRoot = dbFile.parentFile?.parentFile
        val context = buildString {
            for (c in concepts) append(conceptSlice(store, c, question, qVec, embedder.name, repoRoot)).append('\n')
        }.trim()

        if (contextOnly) {
            echo(context)
        } else {
            val llm = LlmFactory.build(
                provider ?: cfg?.provider ?: "ollama",
                model ?: cfg?.model,
                ollamaUrl ?: cfg?.ollamaUrl ?: "http://localhost:11434",
                null, cfg?.baseUrl, cfg?.apiKeyEnv,
            )
            echo("concepts: ${concepts.joinToString(", ") { it.name }}   [answering via ${llm.name}]\n")
            echo(llm.complete(SYSTEM, "Context:\n$context\n\nQuestion: $question").trim())
        }

        UsageLog(File(dbFile.parentFile, "usage.jsonl"))
            .record("ask", question, hit = true, chars = context.length, ms = System.currentTimeMillis() - started)
        conn.close()
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

        val impls = store.implementersOf(c.id)
        val byFile = impls.groupBy { it.ref.substringBefore('#') }
        // Question-aware code drill too: prefer files/symbols whose NAMES share tokens
        // with the question (deleteDocument for "can a document be deleted?"), then size.
        val qTokens = tokens(question)
        fun symScore(title: String?) = tokens(title ?: "").count { it in qTokens }
        val topFiles = byFile.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<dev.stele.core.model.Artifact>>> { e -> e.value.maxOf { symScore(it.title) } }
                .thenByDescending { it.value.size })
            .take(6)
        append("\nCODE DOES (fact) — ${impls.size} symbols across ${byFile.size} files:\n")
        val spans = store.symbolSpans(topFiles.take(3).flatMap { it.value }.map { it.id })
        var bodies = 0
        for ((file, syms) in topFiles) {
            val ranked = syms.sortedByDescending { symScore(it.title) }
            append("  $file: ${ranked.take(10).joinToString(", ") { it.title ?: it.ref }}\n")
            if (bodies >= MAX_BODIES || repoRoot == null) continue
            val src = File(repoRoot, file).takeIf { it.isFile }?.let { runCatching { it.readLines() }.getOrNull() } ?: continue
            for (s in ranked) {
                if (bodies >= MAX_BODIES) break
                val span = spans[s.id] ?: continue
                val lines = src.subList((span.first - 1).coerceIn(0, src.size), span.last.coerceAtMost(src.size))
                if (lines.isEmpty()) continue
                append("    ─ ${s.title} ($file:${span.first}):\n")
                for (line in lines.take(MAX_BODY_LINES)) append("      $line\n")
                bodies++
            }
        }
    }

    /** camelCase/kebab/snake-aware lowercase tokens. */
    private fun tokens(s: String): Set<String> =
        s.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()

    companion object {
        private const val MAX_BODIES = 4
        private const val MAX_BODY_LINES = 40

        private val SYSTEM = """
            You are a support engineer answering a product question for a colleague.
            The context has two sides: DOCS SAY (intent: docs + product rules) and CODE DOES (fact: real code).
            Answer ONLY from the context, concisely, in the language of the question:
            1) How it should work (per docs/rules) — cite the doc section.
            2) How it actually works (per code) — cite file names.
            3) MISMATCH: only if docs and code genuinely disagree, say so explicitly.
            If the context does not contain the answer, say exactly: I don't know from the available context.
        """.trimIndent()
    }
}
