package dev.stele.cli

import dev.stele.cli.config.LlmConfig
import dev.stele.core.embed.Embedder
import dev.stele.resolver.HashingEmbedder
import dev.stele.resolver.OllamaEmbedder

/** Resolves the embedding backend: flags > stele.yml > offline hashing default. */
object EmbedderFactory {
    fun build(provider: String, model: String, ollamaUrl: String): Embedder =
        when (provider.lowercase()) {
            "ollama" -> OllamaEmbedder(model, ollamaUrl)
            else -> HashingEmbedder()
        }

    fun fromConfig(cfg: LlmConfig?, provider: String? = null, model: String? = null, ollamaUrl: String? = null): Embedder =
        build(
            provider ?: cfg?.embedProvider ?: "hashing",
            model ?: cfg?.embedModel ?: "nomic-embed-text",
            ollamaUrl ?: cfg?.ollamaUrl ?: "http://localhost:11434",
        )

    /**
     * A one-line warning when the active embedder is the offline lexical FLOOR — the
     * out-of-the-box default runs below the measured nomic numbers, and that must be visible.
     * Returns null for a real embedding model.
     */
    fun floorWarning(embedder: Embedder): String? =
        if (embedder.name.startsWith("hashing")) {
            "note: semantic layer is the offline lexical floor (${embedder.name}) — for the measured quality " +
                "set `embedProvider: ollama` (nomic-embed-text) in stele.yml, then `stele embed`."
        } else {
            null
        }
}
