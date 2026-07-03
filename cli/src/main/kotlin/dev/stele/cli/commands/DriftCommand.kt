package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.CodeSlices
import dev.stele.cli.EmbedderFactory
import dev.stele.cli.LlmFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.model.Concept
import dev.stele.core.store.GraphStore
import dev.stele.resolver.DriftChecker
import dev.stele.resolver.DriftFinding
import dev.stele.resolver.DriftVerdict

/**
 * The "docs say vs code does" reconciliation, proactively: every product rule a
 * concept carries is audited against the code that implements the concept.
 * VIOLATIONs surface the silent divergence nobody tracks — documentation that
 * promises 15 minutes while the code does 24 hours.
 */
class DriftCommand : CliktCommand(
    name = "drift",
    help = "Audit a concept's product rules against its implementation (docs-say vs code-does)",
) {
    private val concept by argument(name = "concept", help = "Concept name/alias (omit with --all)").optional()
    private val all by option("--all", help = "Audit every resolved concept that carries rules").flag()
    private val limit by option("--limit", help = "Max rules per concept").int().default(8)
    private val provider by option("--provider", help = "LLM provider for the audit")
    private val model by option("--model")
    private val ollamaUrl by option("--ollama-url")

    override fun run() {
        val dbFile = requireDb()
        val conn = openDb(dbFile.path)
        val store = GraphStore(conn)
        val cfg = ConfigLoader.findAndLoad()?.llm
        val llm = LlmFactory.build(
            provider ?: cfg?.provider ?: "ollama",
            model ?: cfg?.model,
            ollamaUrl ?: cfg?.ollamaUrl ?: "http://localhost:11434",
            null, cfg?.baseUrl, cfg?.apiKeyEnv,
        )
        val checker = DriftChecker(llm)
        val repoRoot = dbFile.parentFile?.parentFile

        val targets: List<Concept> = when {
            all -> store.resolvedConcepts().filter { store.rulesFor(it.id).isNotEmpty() }
            concept != null -> listOfNotNull(resolve(store, concept!!)).ifEmpty {
                echo("No concept matching \"$concept\".")
                conn.close()
                return
            }
            else -> {
                echo("Provide a concept name, or --all.")
                conn.close()
                return
            }
        }

        echo("auditing via ${llm.name} …\n")
        val findings = mutableListOf<DriftFinding>()
        var skippedCodeLike = 0
        for (c in targets) {
            val rules = store.rulesFor(c.id).mapNotNull { it.title }
                .filterNot { looksLikeCode(it).also { skip -> if (skip) skippedCodeLike++ } }
                .take(limit)
            for (text in rules) {
                val slice = CodeSlices.forConcept(store, c.id, text, repoRoot, maxFiles = 4, maxBodies = 3)
                findings += checker.check(c.name, text, slice.text, slice.files)
            }
        }
        if (skippedCodeLike > 0) echo("(skipped $skippedCodeLike code-looking rules — run `stele refine-rules` to clean them up)\n")

        if (findings.isEmpty()) {
            echo("No rules to audit — run `stele sync` (docs carry the rules) first.")
            conn.close()
            return
        }
        // Violations first — they are the product.
        for (f in findings.sortedBy { it.verdict.ordinal }) {
            val mark = when (f.verdict) {
                DriftVerdict.VIOLATION -> "✗ VIOLATION   "
                DriftVerdict.UNVERIFIABLE -> "? unverifiable"
                DriftVerdict.COMPLIANT -> "✓ compliant   "
            }
            echo("$mark [${f.concept}] ${f.rule.take(110)}")
            echo("               ${f.reason}")
            f.files.firstOrNull()?.let { echo("               code: ${f.files.take(3).joinToString(", ")}") }
            echo("")
        }
        val counts = findings.groupingBy { it.verdict }.eachCount()
        echo(
            "drift: ${counts[DriftVerdict.VIOLATION] ?: 0} violation(s), " +
                "${counts[DriftVerdict.COMPLIANT] ?: 0} compliant, " +
                "${counts[DriftVerdict.UNVERIFIABLE] ?: 0} unverifiable of ${findings.size} rules",
        )
        conn.close()
    }

    /**
     * A "rule" that is really a code/JSX fragment (extraction noise) can't be
     * meaningfully audited — an LLM will happily "verify" that `require('path')`
     * is complied with. `refine-rules` is the real cleanup; this is the guard.
     */
    private fun looksLikeCode(s: String): Boolean =
        s.startsWith("<") || s.contains("require(") || s.contains("=>") ||
            s.contains("');") || s.contains("\");") ||
            s.trim().endsWith(":") || // truncated list-header — the actual rule body was lost
            Regex("^(const|let|var|throw|import|export|function|return|if)\\b").containsMatchIn(s.trim())

    /** Exact name/alias first, then the stored semantic layer. */
    private fun resolve(store: GraphStore, term: String): Concept? {
        store.resolveConcept(term)?.let { return it }
        val embedder = EmbedderFactory.fromConfig(ConfigLoader.findAndLoad()?.llm)
        val qVec = runCatching { embedder.embedQuery(term) }.getOrNull() ?: return null
        return store.resolveSemanticConcepts(qVec, embedder.name, topK = 1).firstOrNull()
    }
}
