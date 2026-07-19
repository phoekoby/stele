package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
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
import dev.stele.resolver.LlmClient
import java.io.File

/**
 * The "docs say vs code does" reconciliation, proactively: every product rule a
 * concept carries is audited against the code that implements the concept.
 * VIOLATIONs surface the silent divergence nobody tracks — documentation that
 * promises 15 minutes while the code does 24 hours.
 *
 * `--diff` is the PR/CI mode: route a git diff through the graph (changed files →
 * concepts → their rules, ranked by overlap with the added lines) and audit only
 * what the change touches. The routing is fully deterministic — no LLM — so
 * `--routes-only` is exact; verdicts are the swappable LLM layer on top.
 * Exit code 1 when a VIOLATION is found (CI gate).
 */
class DriftCommand : CliktCommand(
    name = "drift",
    help = "Audit product rules against the implementation (docs-say vs code-does); --diff audits a change (CI gate)",
) {
    private val concept by argument(name = "concept", help = "Concept name/alias (omit with --all or --diff)").optional()
    private val all by option("--all", help = "Audit every resolved concept that carries rules").flag()
    private val diff by option("--diff", help = "Audit a git diff: a ref/range (HEAD~1, main..HEAD) or 'worktree' for uncommitted changes")
    private val routesOnly by option("--routes-only", help = "With --diff: only print which concepts/rules the change touches (no LLM)").flag()
    private val limit by option("--limit", help = "Max rules per concept (or per diff)").int().default(8)
    private val provider by option("--provider", help = "LLM provider for the audit")
    private val model by option("--model")
    private val ollamaUrl by option("--ollama-url")

    private fun buildLlm(cfg: dev.stele.cli.config.LlmConfig?): LlmClient = LlmFactory.build(
        provider ?: cfg?.provider ?: "ollama",
        model ?: cfg?.model,
        ollamaUrl ?: cfg?.ollamaUrl ?: "http://localhost:11434",
        null, cfg?.baseUrl, cfg?.apiKeyEnv,
    )

    override fun run() {
        val dbFile = requireDb()
        val conn = openDb(dbFile.path)
        val store = GraphStore(conn)
        val cfg = ConfigLoader.findAndLoad()?.llm
        val repoRoot = dbFile.parentFile?.parentFile

        if (diff != null) {
            try {
                runDiffAudit(store, cfg, repoRoot, diff!!)
            } finally {
                conn.close()
            }
            return
        }

        val llm = buildLlm(cfg)
        val checker = DriftChecker(llm)

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
        printFindings(findings)
        conn.close()
    }

    // --- the PR/CI mode: route a git diff through the graph, audit what it touches ---

    private class RoutedRule(val rule: String, val concept: String, val file: String, val overlap: Int)

    private fun runDiffAudit(store: GraphStore, cfg: dev.stele.cli.config.LlmConfig?, repoRoot: File?, ref: String) {
        val root = repoRoot ?: File(".")
        val raw = gitDiff(root, ref)
        val files = parseDiff(raw)
        if (files.isEmpty()) {
            echo("No changes in the diff ($ref).")
            return
        }

        // Deterministic routing: changed file → concepts (graph) → their rules,
        // ranked by STEMMED token overlap between the rule text and the ADDED lines
        // (prose says "documents…deleted", code says "deleteDocument" — a raw token
        // compare misses exactly the matches that matter).
        val routed = LinkedHashMap<String, RoutedRule>() // rule text → best route
        val fileConcepts = LinkedHashMap<String, List<String>>()
        for (fd in files) {
            val concepts = store.conceptsForPath(fd.path).map { it.first }
            fileConcepts[fd.path] = concepts.map { it.name }
            val addedStems = CodeSlices.tokens(fd.added.toString()).map(::stem).toSet()
            for (c in concepts) {
                for (r in store.rulesFor(c.id).mapNotNull { it.title }.filterNot(::looksLikeCode)) {
                    val overlap = CodeSlices.tokens(r).map(::stem).toSet().count { it in addedStems }
                    val prev = routed[r]
                    if (prev == null || overlap > prev.overlap) routed[r] = RoutedRule(r, c.name, fd.path, overlap)
                }
            }
        }

        echo("diff: ${files.size} file(s) changed ($ref)")
        for ((path, concepts) in fileConcepts) {
            echo("  $path → ${if (concepts.isEmpty()) "(no concept — not indexed or gated out)" else concepts.joinToString(", ")}")
        }
        val ranked = routed.values.sortedByDescending { it.overlap }
        echo("\napplicable rules (${ranked.size}, ranked by overlap with the change):")
        for (r in ranked.take(limit)) echo("  ‣ [${r.concept}] ${r.rule.take(120)}  (${r.file}, overlap ${r.overlap})")
        if (ranked.size > limit) echo("  … and ${ranked.size - limit} more (raise --limit)")

        if (routesOnly || ranked.isEmpty()) return

        val llm = buildLlm(cfg)
        val checker = DriftChecker(llm)
        echo("\nauditing top ${minOf(limit, ranked.size)} rule(s) via ${llm.name} …\n")
        val findings = mutableListOf<DriftFinding>()
        val hunksByPath = files.associateBy({ it.path }, { it.hunks.toString().take(4000) })
        for (r in ranked.take(limit)) {
            val slice = "THE CHANGE (git diff for ${r.file}; lines starting with '+' are being added):\n${hunksByPath[r.file] ?: ""}"
            findings += checker.check(r.concept, r.rule, slice, listOf(r.file))
        }
        printFindings(findings)
        if (findings.any { it.verdict == DriftVerdict.VIOLATION }) throw ProgramResult(1) // the CI gate
    }

    /** `git diff <ref>` (or the working tree for 'worktree'), run at the graph root. */
    private fun gitDiff(root: File, ref: String): String {
        val args = buildList {
            add("git"); add("diff"); add("--unified=3"); add("--no-color")
            if (!ref.equals("worktree", ignoreCase = true)) add(ref)
        }
        val p = ProcessBuilder(args).directory(root).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw ProgramResult(2).also { echo("git diff failed:\n${out.take(400)}", err = true) }
        return out
    }

    private fun printFindings(findings: List<DriftFinding>) {
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

// --- diff routing primitives (top-level for testability) ---

internal class FileDiff(val path: String) {
    val hunks = StringBuilder()
    val added = StringBuilder()
}

/**
 * Crude prefix-stem for ranking only: "documents"/"document", "deleted"/"delete",
 * "completed"/"complete" all collapse to one key. Collisions are fine — this
 * orders candidate rules, it doesn't decide anything.
 */
internal fun stem(t: String): String = if (t.length > 5) t.take(5) else t

internal fun parseDiff(text: String): List<FileDiff> {
    val out = mutableListOf<FileDiff>()
    var current: FileDiff? = null
    for (line in text.lineSequence()) {
        when {
            line.startsWith("+++ b/") -> { current = FileDiff(line.removePrefix("+++ b/")); out.add(current) }
            line.startsWith("+++") -> current = null // deleted file (+++ /dev/null)
            line.startsWith("diff --git") -> current = null
            current != null && (line.startsWith("@@") || line.startsWith("+") || line.startsWith("-") || line.startsWith(" ")) -> {
                current.hunks.append(line).append('\n')
                if (line.startsWith("+")) current.added.append(line, 1, line.length).append('\n')
            }
        }
    }
    return out
}
