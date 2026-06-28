package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.LlmFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.resolver.canonicalize

class BuildOntologyCommand : CliktCommand(
    name = "build-ontology",
    help = "Canonicalize candidate concepts with an LLM: name/definition/bounded-context + drop noise",
) {
    // Flags override stele.yml; stele.yml overrides the built-in defaults (local Ollama).
    private val provider by option("--provider", help = "ollama (local, default) | anthropic")
    private val model by option("--model", help = "model id (provider-specific)")
    private val ollamaUrl by option("--ollama-url", help = "Ollama base URL")
    private val batch by option("--batch", help = "Concepts per LLM call").int()
    private val responses by option("--responses", help = "Offline: read the LLM JSON response from a file")

    override fun run() {
        val cfg = ConfigLoader.findAndLoad()?.llm
        val llm = LlmFactory.build(
            provider ?: cfg?.provider ?: "ollama",
            model ?: cfg?.model,
            ollamaUrl ?: cfg?.ollamaUrl ?: "http://localhost:11434",
            responses,
            cfg?.baseUrl,
            cfg?.apiKeyEnv,
        )

        val conn = openDb(requireDb().path)
        echo("canonicalizing candidate concepts via ${llm.name} …")
        val res = canonicalize(GraphStore(conn), llm, batchSize = batch ?: cfg?.batch ?: 8)
        conn.close()
        echo(
            "✓ ontology: ${res.kept} concepts kept (${res.renamed} renamed), " +
                "${res.dropped} dropped, ${res.skipped} left unresolved, of ${res.processed} candidates",
        )
    }
}
