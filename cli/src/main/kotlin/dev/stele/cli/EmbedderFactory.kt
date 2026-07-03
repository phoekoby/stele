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
}
