package dev.stele.cli.config

import kotlinx.serialization.Serializable

/** The `stele.yml` schema. Everything has a default, so a partial file is valid. */
@Serializable
data class SteleConfig(
    val llm: LlmConfig = LlmConfig(),
    val sources: List<SourceConfig> = emptyList(),
    val review: ReviewConfig = ReviewConfig(),
    val hooks: HooksConfig = HooksConfig(),
)

@Serializable
data class LlmConfig(
    val provider: String = "ollama",
    val model: String? = null,
    val ollamaUrl: String = "http://localhost:11434",
    val batch: Int = 8,
)

@Serializable
data class SourceConfig(
    val type: String,
    val path: String? = null,
    val urls: List<String> = emptyList(),
)

@Serializable
data class ReviewConfig(val acceptAbove: Double? = null)

@Serializable
data class HooksConfig(val autoReindex: Boolean = false)
