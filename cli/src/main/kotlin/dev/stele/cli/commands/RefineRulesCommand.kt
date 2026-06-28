package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.LlmFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.resolver.refineRules

class RefineRulesCommand : CliktCommand(
    name = "refine-rules",
    help = "Curate scraped product rules with an LLM: keep genuine invariants (rewritten), drop doc noise",
) {
    // Flags override stele.yml; stele.yml overrides the built-in defaults (local Ollama).
    private val provider by option("--provider", help = "ollama (local, default) | anthropic")
    private val model by option("--model", help = "model id (provider-specific)")
    private val ollamaUrl by option("--ollama-url", help = "Ollama base URL")
    private val batch by option("--batch", help = "Rules per LLM call").int()
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
        echo("refining product rules via ${llm.name} …")
        val res = refineRules(GraphStore(conn), llm, batchSize = batch ?: cfg?.batch ?: 12)
        conn.close()
        echo(
            "✓ rules: ${res.kept} kept (${res.rewritten} rewritten), ${res.dropped} dropped as noise, " +
                "${res.skipped} left unresolved, of ${res.processed} candidates",
        )
    }
}
