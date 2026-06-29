package dev.stele.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbedderTest {
    private val embedder = HashingEmbedder(dim = 4096)

    @Test
    fun `embedding is L2-normalized so self-cosine is 1`() {
        val v = embedder.embed("authentication login session token")
        assertTrue(cosine(v, v) in 0.999f..1.001f)
    }

    @Test
    fun `strong lexical overlap outranks an unrelated chunk`() {
        val query = embedder.embed("authentication login session")
        // Shares all three query terms — real overlap dominates any stray hash collision.
        val onTopic = embedder.embed("authentication login session token user")
        val offTopic = embedder.embed("billing invoice payment refund charge")
        assertTrue(cosine(query, onTopic) > cosine(query, offTopic))
    }

    @Test
    fun `top-k ranking picks the chunk that shares the query vocabulary`() {
        val query = embedder.embed("how is authorization and rbac enforced")
        val chunks = mapOf(
            "auth" to "authorization rbac role permission policy enforced",
            "billing" to "billing invoice payment refund charge customer",
            "infra" to "kubernetes pod deployment cluster scaling replica",
        ).mapValues { embedder.embed(it.value) }
        val best = chunks.maxBy { cosine(query, it.value) }.key
        assertEquals("auth", best)
    }
}
