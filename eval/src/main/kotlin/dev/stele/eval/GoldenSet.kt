package dev.stele.eval

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The hand-labelled benchmark: support/PM-style questions, each with the concept(s)
 * a correct retrieval must surface and (optionally) the artifacts and a reference
 * answer. This is the ground truth the three retrieval arms are scored against.
 */
@Serializable
data class GoldenSet(
    val name: String = "stele-eval",
    val questions: List<GoldenQuestion> = emptyList(),
)

@Serializable
data class GoldenQuestion(
    val id: String,
    val question: String,
    /** Concept name(s) (or aliases) a correct retrieval must resolve to. */
    val concepts: List<String> = emptyList(),
    /** Optional repo-relative files/refs that should appear in the retrieved context. */
    val artifacts: List<String> = emptyList(),
    /** Optional short reference answer; enables LLM-judge scoring when `--answer` is on. */
    val answer: String? = null,
    val tags: List<String> = emptyList(),
)

object GoldenLoader {
    // Lenient like ConfigLoader: unknown keys don't break the run.
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun parse(text: String): GoldenSet = yaml.decodeFromString(GoldenSet.serializer(), text)

    fun load(file: File): GoldenSet = parse(file.readText())
}
