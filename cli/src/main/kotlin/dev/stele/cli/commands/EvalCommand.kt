package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.stele.cli.LlmFactory
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.eval.AgenticArm
import dev.stele.eval.Answerer
import dev.stele.eval.Embedder
import dev.stele.eval.EvalRunner
import dev.stele.eval.GoldenLoader
import dev.stele.eval.HashingEmbedder
import dev.stele.eval.Judge
import dev.stele.eval.OllamaEmbedder
import dev.stele.eval.RetrievalArm
import dev.stele.eval.SteleArm
import dev.stele.eval.VectorRagArm
import dev.stele.eval.renderReport
import java.io.File

/**
 * Phase 0: benchmark retrieval arms on a golden Q&A set. Concept-resolution accuracy
 * and token cost need no LLM; `--answer` additionally answers with a (small) model and
 * LLM-judges it against the reference answers. This is the gate before building more.
 */
class EvalCommand : CliktCommand(
    name = "eval",
    help = "Benchmark retrieval (stele vs vector vs agentic) on a golden Q&A set",
) {
    private val golden by option("--golden", help = "Golden Q&A YAML file").required()
    private val db by option("--db", help = "Graph db (defaults to ./.stele/graph.db)")
    private val armsOpt by option("--arms", help = "Comma-separated: stele,stele-sem,vector,agentic").default("stele")
    private val repo by option("--repo", help = "Repo root the vector arm indexes").default(".")
    private val embedProvider by option("--embed-provider", help = "Vector arm embedder: hashing|ollama").default("hashing")
    private val embedModel by option("--embed-model", help = "Ollama embedding model").default("nomic-embed-text")
    private val verbose by option("--verbose", help = "Per-question rows (misses first)").flag(default = false)
    private val answer by option("--answer", help = "Also answer + LLM-judge (needs a model)").flag(default = false)
    private val provider by option("--provider", help = "LLM provider for answering/judging").default("ollama")
    private val model by option("--model")
    private val judgeModel by option("--judge-model", help = "Judge model (default: --model; use a STRONGER one)")
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val responses by option("--responses", help = "Replay a canned LLM response (offline)")

    override fun run() {
        val gset = GoldenLoader.load(File(golden))
        val conn = openDb(db ?: requireDb().path)
        val store = GraphStore(conn)

        val selected = armsOpt.split(",").map { it.trim().lowercase() }.toSet()
        // One embedder choice for every arm that embeds — a fair comparison changes
        // the retrieval strategy, never the embedding quality.
        fun embedder(): Embedder = when (embedProvider.lowercase()) {
            "ollama" -> OllamaEmbedder(embedModel, ollamaUrl)
            else -> HashingEmbedder(4096)
        }
        val arms = buildList<RetrievalArm> {
            if ("stele" in selected) add(SteleArm(store))
            if ("stele-sem" in selected) add(SteleArm(store, embedder = embedder()))
            if ("vector" in selected) add(VectorRagArm(File(repo), embedder()))
            if ("agentic" in selected) add(AgenticArm())
        }

        val llm = if (answer) LlmFactory.build(provider, model, ollamaUrl, responses) else null
        val judgeLlm = if (answer) LlmFactory.build(provider, judgeModel ?: model, ollamaUrl, responses) else null
        val runner = EvalRunner(gset, llm?.let { Answerer(it) }, judgeLlm?.let { Judge(it) })

        echo("golden: ${gset.name}  (${gset.questions.size} questions, arms: ${selected.joinToString(",")})\n")
        val results = runner.run(arms)
        renderReport(results.map { it.first }) { echo(it) }
        if (verbose) {
            for ((report, qresults) in results) {
                if (qresults.isEmpty()) continue
                echo("\n[${report.arm}] per question (✗ = concept miss):")
                for (q in qresults.sortedBy { it.conceptHit }) {
                    val mark = if (q.conceptHit) "✓" else "✗"
                    val recall = if (q.artifactRecall.isNaN()) " n/a" else "%3.0f%%".format(q.artifactRecall * 100)
                    echo("  $mark ${q.questionId}  recall $recall  ~${q.approxTokens}tok  → ${q.resolvedConcepts.joinToString(", ").ifEmpty { "—" }}")
                }
            }
        }
        conn.close()
    }
}
