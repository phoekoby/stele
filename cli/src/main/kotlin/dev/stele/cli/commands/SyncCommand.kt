package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.stele.cli.ConnectorRegistry
import dev.stele.cli.LlmFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.config.SourceConfig
import dev.stele.cli.requireDb
import dev.stele.core.connector.ConnectorParams
import dev.stele.core.connector.ConnectorPhase
import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.resolver.canonicalize
import dev.stele.resolver.refineRules
import java.io.File

/**
 * One command for the whole pipeline, driven by `stele.yml`. Order matters:
 * code sources seed candidate concepts → the LLM canonicalizes them → dedupe →
 * doc sources attach (they match against the now-canonical names/aliases) →
 * refine rules → auto-confirm the strong edges. So the agent gets a curated
 * graph, not the raw deterministic-first proposals.
 */
class SyncCommand : CliktCommand(
    name = "sync",
    help = "Read stele.yml and run the whole pipeline: sources → ontology → docs → rules → dedupe → review",
) {
    private val config by option("--config", help = "Path to the config (default: ./stele.yml)")
    private val noLlm by option("--no-llm", help = "Skip the LLM steps (ontology/rules) — offline").flag()
    private val responses by option("--responses", help = "Offline: replay an LLM JSON response file")

    override fun run() {
        val cfgFile = config?.let { File(it) } ?: ConfigLoader.find()
        if (cfgFile == null || !cfgFile.isFile) {
            echo("No stele.yml found. Run `stele init` to scaffold one, or pass --config <file>.")
            return
        }
        val cfg = ConfigLoader.load(cfgFile)

        val conn = openDb(requireDb().path)
        migrate(conn)
        val store = GraphStore(conn)

        val code = cfg.sources.filter { ConnectorRegistry[it.type]?.phase != ConnectorPhase.DOC }
        val docs = cfg.sources.filter { ConnectorRegistry[it.type]?.phase == ConnectorPhase.DOC }

        runSources(store, code, "code sources")

        if (!noLlm) {
            val llm = LlmFactory.build(
                cfg.llm.provider, cfg.llm.model, cfg.llm.ollamaUrl, responses, cfg.llm.baseUrl, cfg.llm.apiKeyEnv,
            )
            echo("ontology: canonicalizing via ${llm.name} …")
            // A first-run LLM failure (model not pulled, server down) must be a clean,
            // actionable message — not a stack trace. The graph is safe: canonicalize
            // aborts before changing anything, and a re-run of `sync` resumes (symbols
            // are incremental, resolved concepts stay resolved).
            val o = llmStep(llm.name) { canonicalize(store, llm, batchSize = cfg.llm.batch) }
            echo("  • ${o.kept} concepts kept (${o.renamed} renamed), ${o.dropped} dropped, ${o.skipped} unresolved")

            val d = store.dedupeByName()
            echo("dedupe: merged ${d.merged} duplicate concepts across ${d.groups} names")

            runSources(store, docs, "doc sources")

            echo("rules: refining via ${llm.name} …")
            val r = llmStep(llm.name) { refineRules(store, llm, batchSize = cfg.llm.batch) }
            echo("  • ${r.kept} kept (${r.rewritten} rewritten), ${r.dropped} dropped")
        } else {
            val d = store.dedupeByName()
            echo("dedupe: merged ${d.merged} duplicate concepts across ${d.groups} names")
            runSources(store, docs, "doc sources")
        }

        cfg.review.acceptAbove?.let {
            val n = store.confirmAbove(null, it)
            echo("review: confirmed $n proposed edges (confidence ≥ $it)")
        }

        // Semantic layer last — concepts are canonical and doc sections exist by now.
        val embedder = dev.stele.cli.EmbedderFactory.fromConfig(cfg.llm)
        val (ec, es) = embedAll(store, embedder) { echo(it) }
        echo("embed [${embedder.name}]: $ec concept cards, $es doc sections")

        conn.close()
        echo("✓ sync complete")
    }

    /** Turns raw LLM failures into a clean, actionable CLI error. */
    private fun <T> llmStep(llmName: String, step: () -> T): T =
        try {
            step()
        } catch (e: RuntimeException) {
            throw PrintMessage(
                "LLM step failed via $llmName: ${e.message}\n" +
                    "  • local model missing?  ollama pull <model>   (then check `ollama list` and llm.model in stele.yml)\n" +
                    "  • or run offline:       stele sync --no-llm",
                statusCode = 1,
                printError = true,
            )
        }

    private fun runSources(store: GraphStore, sources: List<SourceConfig>, label: String) {
        if (sources.isEmpty()) return
        echo("$label:")
        for (s in sources) {
            val connector = ConnectorRegistry[s.type]
            if (connector == null) {
                echo("  ? ${s.type}: unknown source (have: ${ConnectorRegistry.types.joinToString(", ")})")
                continue
            }
            val summary = runCatching { connector.ingest(store, ConnectorParams(s.path, s.urls)) }
                .getOrElse { "failed: ${it.message}" }
            echo("  • ${s.type}: $summary")
        }
    }
}
