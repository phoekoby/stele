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
}
