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
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ConceptDedupeTest {
    private lateinit var conn: Connection

    @AfterTest
    fun teardown() {
        if (::conn.isInitialized) conn.close()
    }

    @Test
    fun `merges singular and plural twins, repointing all edges`(@TempDir dir: Path) {
        conn = openDb(dir.resolve("d.db").toString())
        migrate(conn)
        val store = GraphStore(conn)

        val secret = store.addConcept("Secret", definition = "a stored secret")
        val secrets = store.addConcept("Secrets", definition = "the secrets feature") // plural twin
        val s1 = store.addArtifact(ArtifactKind.CODE_SYMBOL, Layer.CODE, "code", "a.go#Encrypt")
        val s2 = store.addArtifact(ArtifactKind.CODE_SYMBOL, Layer.CODE, "code", "b.go#Decrypt")
        store.addEdge(s1, secret, EdgeType.IMPLEMENTS, EdgeSource.INFERRED, 1.0, status = EdgeStatus.CONFIRMED)
        store.addEdge(s2, secrets, EdgeType.IMPLEMENTS, EdgeSource.INFERRED, 1.0, status = EdgeStatus.CONFIRMED)

        val res = store.dedupeByName()

        assertEquals(1, res.merged)
        assertNull(store.getConceptByName("Secrets")) // the plural twin is gone
        assertEquals("Secret", store.resolveConcept("Secrets")?.name) // it now resolves via alias
        assertEquals(2, store.implementersOf(secret).size) // both symbols repointed to the survivor
    }
}
