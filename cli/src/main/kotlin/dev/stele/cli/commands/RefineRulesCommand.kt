package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.resolver.AnthropicClient
import dev.stele.resolver.LlmClient
import dev.stele.resolver.OllamaClient
import dev.stele.resolver.StaticLlmClient
import dev.stele.resolver.refineRules
import java.io.File

class RefineRulesCommand : CliktCommand(
    name = "refine-rules",
    help = "Curate scraped product rules with an LLM: keep genuine invariants (rewritten), drop doc noise",
) {
    // Local-first: default to a local Ollama model. Cloud is opt-in.
    private val provider by option("--provider", help = "ollama (local, default) | anthropic").default("ollama")
    private val model by option("--model", help = "model id (provider-specific)")
    private val ollamaUrl by option("--ollama-url", help = "Ollama base URL").default("http://localhost:11434")
    private val batch by option("--batch", help = "Rules per LLM call").int().default(12)
    private val responses by option("--responses", help = "Offline: read the LLM JSON response from a file")

    override fun run() {
        val llm: LlmClient = when {
            responses != null -> StaticLlmClient(File(responses!!).readText())
            provider == "anthropic" -> {
                val key = System.getenv("ANTHROPIC_API_KEY")
                    ?: throw PrintMessage("Set ANTHROPIC_API_KEY for --provider anthropic.", statusCode = 1, printError = true)
                AnthropicClient(key, model ?: "claude-sonnet-4-6")
            }
            else -> OllamaClient(model ?: "llama3.1", ollamaUrl)
        }

        val conn = openDb(requireDb().path)
        echo("refining product rules via ${llm.name} …")
        val res = refineRules(GraphStore(conn), llm, batchSize = batch)
        conn.close()
        echo(
            "✓ rules: ${res.kept} kept (${res.rewritten} rewritten), ${res.dropped} dropped as noise, " +
                "${res.skipped} left unresolved, of ${res.processed} candidates",
        )
    }
}
