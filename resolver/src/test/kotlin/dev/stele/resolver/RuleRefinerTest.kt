package dev.stele.resolver

import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.model.ArtifactKind
import dev.stele.core.model.EdgeSource
import dev.stele.core.model.EdgeStatus
import dev.stele.core.model.EdgeType
import dev.stele.core.model.Layer
import dev.stele.core.store.GraphStore
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class RuleRefinerTest {
    private lateinit var conn: Connection

    @AfterTest
    fun teardown() {
        if (::conn.isInitialized) conn.close()
    }

    @Test
    fun `keeps a genuine rule (rewritten), drops doc noise, serves only the kept one`(@TempDir dir: Path) {
        conn = openDb(dir.resolve("t.db").toString())
        migrate(conn)
        val store = GraphStore(conn)

        val doc = store.addConcept("Document", definition = "a signed document")
        val good = store.addArtifact(ArtifactKind.RULE, Layer.PRODUCT, "docs", "rule:aaa", "completed docs cant be removed", "completed docs cant be removed")
        val noise = store.addArtifact(ArtifactKind.RULE, Layer.PRODUCT, "docs", "rule:bbb", "| viewer | read-only |", "| viewer | read-only |")
        val keepEdge = store.addEdge(good, doc, EdgeType.CONSTRAINS, EdgeSource.INFERRED, 0.6, status = EdgeStatus.PROPOSED)
        store.addEdge(noise, doc, EdgeType.CONSTRAINS, EdgeSource.INFERRED, 0.6, status = EdgeStatus.PROPOSED)

        val llm = StaticLlmClient(
            """[{"id":1,"keep":true,"refined":"Completed documents cannot be deleted."},{"id":2,"keep":false,"refined":""}]""",
        )
        val res = refineRules(store, llm, batchSize = 50)

        assertEquals(1, res.kept)
        assertEquals(1, res.dropped)
        assertEquals(1, res.rewritten)
        assertEquals(EdgeStatus.CONFIRMED.value, statusOf(keepEdge))
        // The serving gate now exposes only the kept rule, with its refined text.
        assertEquals(listOf("Completed documents cannot be deleted."), store.rulesFor(doc).map { it.title })
    }

    private fun statusOf(edgeId: String): String =
        conn.prepareStatement("SELECT status FROM edges WHERE id = ?").use {
            it.setString(1, edgeId)
            it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
}
