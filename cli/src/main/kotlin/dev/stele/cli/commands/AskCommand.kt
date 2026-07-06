package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.AskService
import dev.stele.cli.EmbedderFactory
import dev.stele.cli.LlmFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
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
        EmbedderFactory.floorWarning(EmbedderFactory.fromConfig(cfg))?.let { echo(it, err = true) }
        val started = System.currentTimeMillis()

        val llm = if (contextOnly) {
            null
        } else {
            LlmFactory.build(
                provider ?: cfg?.provider ?: "ollama",
                model ?: cfg?.model,
                ollamaUrl ?: cfg?.ollamaUrl ?: "http://localhost:11434",
                null, cfg?.baseUrl, cfg?.apiKeyEnv,
            )
        }
        val res = AskService.ask(store, cfg, dbFile.parentFile?.parentFile, question, top, llm)
        if (res == null) {
            echo("Couldn't resolve the question to a concept. Run `stele sync` (incl. `stele embed`) first, or rephrase.")
            conn.close()
            return
        }

        if (res.answer != null) {
            echo("concepts: ${res.concepts.joinToString(", ") { it.name }}   [answering via ${llm!!.name}]\n")
            echo(res.answer)
        } else {
            echo(res.context)
        }

        UsageLog(File(dbFile.parentFile, "usage.jsonl"))
            .record("ask", question, hit = true, chars = res.context.length, ms = System.currentTimeMillis() - started)
        conn.close()
    }
}
