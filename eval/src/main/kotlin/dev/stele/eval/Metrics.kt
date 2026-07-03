package dev.stele.eval

import kotlin.math.sqrt

/** Per-question result for one arm. */
data class QResult(
    val questionId: String,
    val resolvedConcepts: List<String>,
    val conceptHit: Boolean, // resolved ∩ gold concepts non-empty
    val artifactRecall: Double, // fraction of gold artifacts present in context (NaN when none specified)
    val approxTokens: Int, // context size handed to the model (~chars/4)
    val latencyMs: Long,
    val answer: String? = null,
    val scores: List<Int> = emptyList(), // judge scores 1-5, one per sample (K≥1)
) {
    val meanScore: Double? get() = if (scores.isEmpty()) null else scores.average()
}

/** Aggregated metrics for one arm across the golden set. */
data class ArmReport(
    val arm: String,
    val n: Int,
    val conceptHitRate: Double,
    val meanArtifactRecall: Double,
    val meanTokens: Double,
    val meanLatencyMs: Double,
    val meanScore: Double?, // null when judging was off
    val scoreCi95: Double? = null, // ± half-width over per-question mean scores
    val implemented: Boolean = true,
)

/** Paired per-question comparison of two arms on mean judge scores + exact sign test. */
data class PairedComparison(
    val armA: String,
    val armB: String,
    val wins: Int, // A > B
    val losses: Int, // A < B
    val ties: Int,
    val p: Double, // exact two-sided sign test over the discordant pairs
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
        val qMeans = results.mapNotNull { it.meanScore }
        return ArmReport(
            arm = arm,
            n = results.size,
            conceptHitRate = results.count { it.conceptHit }.toDouble() / results.size,
            meanArtifactRecall = if (recalls.isEmpty()) Double.NaN else recalls.average(),
            meanTokens = results.map { it.approxTokens }.average(),
            meanLatencyMs = results.map { it.latencyMs }.average(),
            meanScore = if (qMeans.isEmpty()) null else qMeans.average(),
            scoreCi95 = ci95(qMeans),
        )
    }

    /** 95% CI half-width of the mean (normal approximation over per-question means). */
    private fun ci95(xs: List<Double>): Double? {
        if (xs.size < 2) return null
        val mean = xs.average()
        val variance = xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)
        return 1.96 * sqrt(variance / xs.size)
    }

    /**
     * Paired sign test on per-question mean scores: only questions judged in BOTH arms
     * count; ties carry no signal. Exact two-sided binomial p over the discordant pairs.
     */
    fun paired(a: ArmReport, ra: List<QResult>, b: ArmReport, rb: List<QResult>): PairedComparison? {
        val byIdB = rb.associateBy { it.questionId }
        var wins = 0
        var losses = 0
        var ties = 0
        for (qa in ra) {
            val sa = qa.meanScore ?: continue
            val sb = byIdB[qa.questionId]?.meanScore ?: continue
            when {
                sa > sb -> wins++
                sa < sb -> losses++
                else -> ties++
            }
        }
        if (wins + losses + ties == 0) return null
        return PairedComparison(a.arm, b.arm, wins, losses, ties, signTest(wins, losses))
    }

    /** Exact two-sided sign test: P(X ≤ min(w,l)) * 2 for X ~ Binomial(w+l, 0.5). */
    internal fun signTest(wins: Int, losses: Int): Double {
        val n = wins + losses
        if (n == 0) return 1.0
        val k = minOf(wins, losses)
        var tail = 0.0
        for (i in 0..k) tail += binomial(n, i)
        val p = 2.0 * tail / Math.pow(2.0, n.toDouble())
        return minOf(1.0, p)
    }

    private fun binomial(n: Int, k: Int): Double {
        var res = 1.0
        for (i in 1..k) res = res * (n - k + i) / i
        return res
    }
}
