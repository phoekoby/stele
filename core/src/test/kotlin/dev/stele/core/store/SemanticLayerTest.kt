package dev.stele.core.store

import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.model.ArtifactKind
import dev.stele.core.model.ConceptStatus
import dev.stele.core.model.EdgeSource
import dev.stele.core.model.EdgeStatus
import dev.stele.core.model.EdgeType
import dev.stele.core.model.Layer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SemanticLayerTest {

    private fun store(dir: Path): GraphStore {
        val conn = openDb(dir.resolve("graph.db").toString())
        migrate(conn)
        return GraphStore(conn)
    }

    @Test
    fun `concept vectors round-trip and rank by cosine`(@TempDir dir: Path) {
        val s = store(dir)
        val auth = s.addConcept("Authentication", status = ConceptStatus.CANDIDATE)
        val billing = s.addConcept("Billing", status = ConceptStatus.CANDIDATE)
        s.updateConcept(auth, "Authentication", "How users log in", "IAM", emptyList())
        s.updateConcept(billing, "Billing", "Plans and payments", "Billing", emptyList())

        // Orthogonal-ish toy vectors: query matches auth exactly.
        s.storeConceptVector(auth, "test", floatArrayOf(1f, 0f, 0f))
        s.storeConceptVector(billing, "test", floatArrayOf(0f, 1f, 0f))

        val hits = s.resolveSemanticConcepts(floatArrayOf(0.9f, 0.1f, 0f), "test", topK = 1)
        assertEquals(listOf("Authentication"), hits.map { it.name })
        // Unknown model → no vectors → empty (callers fall back to lexical).
        assertTrue(s.resolveSemanticConcepts(floatArrayOf(1f, 0f, 0f), "other", topK = 1).isEmpty())
    }

    @Test
    fun `question-aware drill reorders sections by stored vectors`(@TempDir dir: Path) {
        val s = store(dir)
        val c = s.addConcept("Billing", status = ConceptStatus.CANDIDATE)
        s.updateConcept(c, "Billing", "Plans and payments", "Billing", emptyList())

        val offTopic = s.addArtifact(ArtifactKind.DOC, Layer.PRODUCT, "docs", "d.md#a", title = "History", body = "old")
        val onTopic = s.addArtifact(ArtifactKind.DOC, Layer.PRODUCT, "docs", "d.md#b", title = "Limits", body = "plan limits")
        // offTopic has the higher edge confidence — confidence order alone would serve it first.
        s.addEdge(offTopic, c, EdgeType.DESCRIBES, EdgeSource.DETERMINISTIC, 0.95, emptyList(), EdgeStatus.PROPOSED)
        s.addEdge(onTopic, c, EdgeType.DESCRIBES, EdgeSource.DETERMINISTIC, 0.9, emptyList(), EdgeStatus.PROPOSED)

        s.storeSectionVector(offTopic, "test", floatArrayOf(0f, 1f))
        s.storeSectionVector(onTopic, "test", floatArrayOf(1f, 0f))

        val docs = s.describingDocs(c)
        assertEquals(listOf("History", "Limits"), docs.map { it.title }) // confidence order
        val ranked = s.rankDocsForQuery(docs, floatArrayOf(1f, 0f), "test")
        assertEquals(listOf("Limits", "History"), ranked.map { it.title }) // question order
    }
}
