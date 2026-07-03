package dev.stele.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsTest {
    @Test
    fun `concept hit is case and whitespace insensitive`() {
        assertTrue(Metrics.conceptHit(listOf("Authentication"), listOf("authentication")))
        assertTrue(Metrics.conceptHit(listOf("Billing", " Auth "), listOf("auth")))
        assertFalse(Metrics.conceptHit(listOf("Billing"), listOf("Authentication")))
    }

    @Test
    fun `empty gold concepts means any resolution counts as a hit`() {
        assertTrue(Metrics.conceptHit(listOf("Anything"), emptyList()))
        assertFalse(Metrics.conceptHit(emptyList(), emptyList()))
    }

    @Test
    fun `artifact recall is fraction of gold refs present, NaN when none specified`() {
        assertEquals(1.0, Metrics.artifactRecall(listOf("apps/auth/login.go"), listOf("apps/auth")))
        assertEquals(0.5, Metrics.artifactRecall(listOf("apps/auth/x.go"), listOf("apps/auth", "apps/billing")))
        assertTrue(Metrics.artifactRecall(listOf("apps/auth"), emptyList()).isNaN())
    }

    @Test
    fun `aggregate computes hit rate and skips NaN recalls`() {
        val results = listOf(
            QResult("q1", listOf("Auth"), conceptHit = true, artifactRecall = 1.0, approxTokens = 100, latencyMs = 10),
            QResult("q2", listOf("Billing"), conceptHit = false, artifactRecall = Double.NaN, approxTokens = 200, latencyMs = 20),
        )
        val r = Metrics.aggregate("stele", results)
        assertEquals(0.5, r.conceptHitRate)
        assertEquals(1.0, r.meanArtifactRecall) // the NaN row is excluded
        assertEquals(150.0, r.meanTokens)
        assertEquals(null, r.meanScore)
    }

    @Test
    fun `K samples aggregate to per-question means and a CI`() {
        val results = listOf(
            QResult("q1", emptyList(), conceptHit = true, artifactRecall = 1.0, approxTokens = 10, latencyMs = 1, scores = listOf(4, 5, 3)),
            QResult("q2", emptyList(), conceptHit = true, artifactRecall = 1.0, approxTokens = 10, latencyMs = 1, scores = listOf(2, 2, 2)),
        )
        val r = Metrics.aggregate("x", results)
        assertEquals(3.0, r.meanScore) // mean of per-question means (4, 2)
        assertTrue((r.scoreCi95 ?: 0.0) > 0.0)
    }

    @Test
    fun `exact sign test matches known values`() {
        assertEquals(1.0, Metrics.signTest(0, 0))
        // 6 wins / 0 losses: p = 2 * (1/64) = 0.03125 — significant
        assertEquals(0.03125, Metrics.signTest(6, 0), 1e-9)
        // 10 vs 6: comfortably not significant
        assertTrue(Metrics.signTest(10, 6) > 0.4)
    }

    @Test
    fun `paired comparison counts wins losses ties on shared judged questions`() {
        val a = listOf(
            QResult("q1", emptyList(), true, 1.0, 1, 1, scores = listOf(5)),
            QResult("q2", emptyList(), true, 1.0, 1, 1, scores = listOf(3)),
            QResult("q3", emptyList(), true, 1.0, 1, 1, scores = listOf(4)),
            QResult("q4", emptyList(), true, 1.0, 1, 1), // unjudged — excluded
        )
        val b = listOf(
            QResult("q1", emptyList(), true, 1.0, 1, 1, scores = listOf(3)),
            QResult("q2", emptyList(), true, 1.0, 1, 1, scores = listOf(3)),
            QResult("q3", emptyList(), true, 1.0, 1, 1, scores = listOf(5)),
            QResult("q4", emptyList(), true, 1.0, 1, 1, scores = listOf(5)),
        )
        val p = Metrics.paired(Metrics.aggregate("a", a), a, Metrics.aggregate("b", b), b)!!
        assertEquals(1, p.wins)
        assertEquals(1, p.losses)
        assertEquals(1, p.ties)
    }
}
