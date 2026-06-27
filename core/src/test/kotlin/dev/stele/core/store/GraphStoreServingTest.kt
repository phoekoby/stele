package dev.stele.core.store

import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.model.ArtifactKind
import dev.stele.core.model.EdgeSource
import dev.stele.core.model.EdgeStatus
import dev.stele.core.model.EdgeType
import dev.stele.core.model.Layer
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class GraphStoreServingTest {
    private lateinit var conn: Connection
    private lateinit var store: GraphStore

    private fun setup(dir: Path) {
        conn = openDb(dir.resolve("test.db").toString())
        migrate(conn)
        store = GraphStore(conn)
    }

    @AfterTest
    fun teardown() {
        if (::conn.isInitialized) conn.close()
    }

    @Test
    fun `describes gate keeps heading-strength, drops body mentions`(@TempDir dir: Path) {
        setup(dir)
        val auth = store.addConcept("Auth", definition = "verifies identity")
        val strong = store.addArtifact(ArtifactKind.DOC, Layer.PRODUCT, "docs", "a.md#h", "Auth")
        val weak = store.addArtifact(ArtifactKind.DOC, Layer.PRODUCT, "docs", "b.md#x", "misc")
        store.addEdge(strong, auth, EdgeType.DESCRIBES, EdgeSource.DETERMINISTIC, 0.9, status = EdgeStatus.PROPOSED)
        store.addEdge(weak, auth, EdgeType.DESCRIBES, EdgeSource.DETERMINISTIC, 0.6, status = EdgeStatus.PROPOSED)
        assertEquals(listOf("a.md#h"), store.describingDocs(auth).map { it.ref })
    }

    @Test
    fun `relates gate drops weak edges and unresolved concepts`(@TempDir dir: Path) {
        setup(dir)
        val auth = store.addConcept("Auth", definition = "x")
        val strong = store.addConcept("Strong", definition = "y")
        val weak = store.addConcept("Weak", definition = "z")
        val ghost = store.addConcept("Ghost") // no definition → unresolved, must not be served
        store.addEdge(auth, strong, EdgeType.RELATES, EdgeSource.INFERRED, 0.9, status = EdgeStatus.PROPOSED)
        store.addEdge(auth, weak, EdgeType.RELATES, EdgeSource.INFERRED, 0.5, status = EdgeStatus.PROPOSED)
        store.addEdge(auth, ghost, EdgeType.RELATES, EdgeSource.INFERRED, 0.9, status = EdgeStatus.PROPOSED)
        assertEquals(listOf("Strong"), store.relatedConcepts(auth).map { it.name })
    }

    @Test
    fun `callsForPath splits outgoing and external incoming`(@TempDir dir: Path) {
        setup(dir)
        val login = store.addArtifact(ArtifactKind.CODE_SYMBOL, Layer.CODE, "code", "auth.go#Login")
        val helper = store.addArtifact(ArtifactKind.CODE_SYMBOL, Layer.CODE, "code", "helper.go#Helper")
        val caller = store.addArtifact(ArtifactKind.CODE_SYMBOL, Layer.CODE, "code", "cli.go#Caller")
        store.addEdge(login, helper, EdgeType.CALLS, EdgeSource.DETERMINISTIC, 1.0, status = EdgeStatus.CONFIRMED)
        store.addEdge(caller, login, EdgeType.CALLS, EdgeSource.DETERMINISTIC, 1.0, status = EdgeStatus.CONFIRMED)
        val (out, incoming) = store.callsForPath("auth.go")
        assertEquals(listOf("helper.go#Helper"), out.map { it.toRef })
        assertEquals(listOf("cli.go#Caller"), incoming.map { it.fromRef })
    }
}
