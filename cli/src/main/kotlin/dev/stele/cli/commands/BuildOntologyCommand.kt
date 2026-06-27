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
import dev.stele.resolver.canonicalize
import java.io.File

class BuildOntologyCommand : CliktCommand(
    name = "build-ontology",
    help = "Canonicalize candidate concepts with an LLM: name/definition/bounded-context + drop noise",
) {
    // Local-first: default to a local Ollama model (offline, free, OSS). Cloud is opt-in.
    private val provider by option("--provider", help = "ollama (local, default) | anthropic").default("ollama")
    private val model by option("--model", help = "model id (provider-specific)")
    private val ollamaUrl by option("--ollama-url", help = "Ollama base URL").default("http://localhost:11434")
    private val batch by option("--batch", help = "Concepts per LLM call").int().default(8)
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
        echo("canonicalizing candidate concepts via ${llm.name} …")
        val res = canonicalize(GraphStore(conn), llm, batchSize = batch)
        conn.close()
        echo(
            "✓ ontology: ${res.kept} concepts kept (${res.renamed} renamed), " +
                "${res.dropped} dropped, ${res.skipped} left unresolved, of ${res.processed} candidates",
        )
    }
}
