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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class CanonicalizeTest {
    private lateinit var conn: Connection
    private lateinit var store: GraphStore

    private fun setup(dir: Path) {
        conn = openDb(dir.resolve("t.db").toString())
        migrate(conn)
        store = GraphStore(conn)
    }

    @AfterTest fun teardown() { if (::conn.isInitialized) conn.close() }

    /** A candidate concept = folder cluster with an implementing code symbol, no definition yet. */
    private fun candidate(name: String) {
        val cid = store.addConcept(name) // CANDIDATE, definition NULL
        val sid = store.addArtifact(ArtifactKind.CODE_SYMBOL, Layer.CODE, "code", "app/${name.lowercase()}/x.go#F", "F")
        store.addEdge(sid, cid, EdgeType.IMPLEMENTS, EdgeSource.INFERRED, 0.6, status = EdgeStatus.PROPOSED)
    }

    @Test
    fun `verdict matches its cluster despite casing and whitespace drift`(@TempDir dir: Path) {
        setup(dir)
        candidate("Auth")
        candidate("Billing")
        // The 8B echoes the cluster with different casing/spacing — must NOT be dropped.
        val llm = StaticLlmClient(
            """[
              {"cluster":"  auth ","keep":true,"name":"Authentication","definition":"Verifies identity.","confidence":0.9},
              {"cluster":"BILLING","keep":true,"name":"Billing","definition":"Plans and payments.","confidence":0.9}
            ]""",
        )
        val r = canonicalize(store, llm, batchSize = 100)
        assertEquals(2, r.kept, "both matched despite case/whitespace drift")
        assertEquals(0, r.skipped, "nothing silently skipped")
        assertEquals(0, store.candidateConcepts().size, "both resolved (definition set)")
    }

    @Test
    fun `a rename keeps the original cluster name resolvable via alias`(@TempDir dir: Path) {
        setup(dir)
        candidate("Auth")
        val llm = StaticLlmClient(
            """[{"cluster":"Auth","keep":true,"name":"Authentication","definition":"Verifies identity.","confidence":0.9}]""",
        )
        canonicalize(store, llm, batchSize = 100)
        assertNotNull(store.resolveConcept("Authentication"), "resolves by the new canonical name")
        assertNotNull(store.resolveConcept("Auth"), "still resolves by the original folder term (folded to alias)")
    }

    @Test
    fun `a total parse failure aborts without changing the spine`(@TempDir dir: Path) {
        setup(dir)
        candidate("Auth")
        val llm = StaticLlmClient("the model rambled and returned no JSON array")
        assertFailsWith<RuntimeException> { canonicalize(store, llm, batchSize = 100) }
        assertEquals(1, store.candidateConcepts().size, "unchanged — still a candidate")
    }

    @Test
    fun `low-confidence or keep-false drops the concept`(@TempDir dir: Path) {
        setup(dir)
        candidate("Psql") // infra noise
        val llm = StaticLlmClient(
            """[{"cluster":"Psql","keep":false,"confidence":0.9}]""",
        )
        val r = canonicalize(store, llm, batchSize = 100)
        assertEquals(1, r.dropped)
        assertNull(store.resolveConcept("Psql"), "dropped from the graph")
    }
}
