package dev.stele.eval

/** Per-question result for one arm. */
data class QResult(
    val questionId: String,
    val resolvedConcepts: List<String>,
    val conceptHit: Boolean, // resolved ∩ gold concepts non-empty
    val artifactRecall: Double, // fraction of gold artifacts present in context (NaN when none specified)
    val approxTokens: Int, // context size handed to the model (~chars/4)
    val latencyMs: Long,
    val answer: String? = null,
    val score: Int? = null, // judge score 1-5
)

/** Aggregated metrics for one arm across the golden set. */
data class ArmReport(
    val arm: String,
    val n: Int,
    val conceptHitRate: Double,
    val meanArtifactRecall: Double,
    val meanTokens: Double,
    val meanLatencyMs: Double,
    val meanScore: Double?, // null when judging was off
    val implemented: Boolean = true,
)

object Metrics {
    /** Cheap, model-free token proxy good enough for relative comparison across arms. */
    fun approxTokens(text: String): Int = if (text.isBlank()) 0 else (text.length + 3) / 4

    fun conceptHit(resolved: List<String>, gold: List<String>): Boolean {
        if (gold.isEmpty()) return resolved.isNotEmpty()
        val r = resolved.map { it.trim().lowercase() }.toSet()
        return gold.any { it.trim().lowercase() in r }
    }

    fun artifactRecall(refs: List<String>, gold: List<String>): Double {
        if (gold.isEmpty()) return Double.NaN
        val hay = refs.joinToString("\n").lowercase()
        return gold.count { hay.contains(it.lowercase()) }.toDouble() / gold.size
    }

    fun aggregate(arm: String, results: List<QResult>): ArmReport {
        if (results.isEmpty()) return ArmReport(arm, 0, 0.0, Double.NaN, 0.0, 0.0, null)
        val recalls = results.map { it.artifactRecall }.filter { !it.isNaN() }
        val scores = results.mapNotNull { it.score }
        return ArmReport(
            arm = arm,
            n = results.size,
            conceptHitRate = results.count { it.conceptHit }.toDouble() / results.size,
            meanArtifactRecall = if (recalls.isEmpty()) Double.NaN else recalls.average(),
            meanTokens = results.map { it.approxTokens }.average(),
            meanLatencyMs = results.map { it.latencyMs }.average(),
            meanScore = if (scores.isEmpty()) null else scores.average(),
        )
    }
}
