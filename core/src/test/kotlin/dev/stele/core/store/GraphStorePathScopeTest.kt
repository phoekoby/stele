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
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class GraphStorePathScopeTest {
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

    private fun symbol(ref: String, concept: String): String {
        val cid = store.addConcept(concept, definition = "def of $concept")
        val sid = store.addArtifact(ArtifactKind.CODE_SYMBOL, Layer.CODE, "code", ref, ref.substringAfter('#'))
        store.addEdge(sid, cid, EdgeType.IMPLEMENTS, EdgeSource.INFERRED, 0.6, status = EdgeStatus.PROPOSED)
        return cid
    }

    @Test
    fun `conceptsForPath anchors to a separator — a sibling directory does not bleed in`(@TempDir dir: Path) {
        setup(dir)
        symbol("apps/auth/login.go#Login", "Auth")
        symbol("apps/authz/rbac.go#Check", "Authz") // sibling dir sharing the 'auth' prefix

        val got = store.conceptsForPath("apps/auth").map { it.first.name }.toSet()
        assertEquals(setOf("Auth"), got, "apps/auth must not match apps/authz")
    }

    @Test
    fun `deleteFileArtifacts escapes underscore — it does not delete a look-alike sibling file`(@TempDir dir: Path) {
        setup(dir)
        // '_' is a LIKE single-char wildcard; unescaped, user_service matched userXservice.
        val keep = symbol("apps/user_service.go#Handler", "UserService")
        val other = symbol("apps/userXservice.go#Other", "OtherService")

        store.deleteFileArtifacts("apps/user_service.go")

        assertTrue(store.implementersOf(keep).isEmpty(), "the target file's symbols are gone")
        assertEquals(
            listOf("apps/userXservice.go#Other"),
            store.implementersOf(other).map { it.ref },
            "the underscore must not have deleted the look-alike sibling",
        )
    }

    @Test
    fun `deleteFileArtifacts removes edges and artifacts together`(@TempDir dir: Path) {
        setup(dir)
        val cid = symbol("pkg/a.go#Foo", "Feature")
        store.deleteFileArtifacts("pkg/a.go")
        assertTrue(store.implementersOf(cid).isEmpty())
        // No dangling edge left pointing at the deleted symbol.
        assertEquals(0, danglingEdges(), "edges touching deleted artifacts must be gone")
    }

    private fun danglingEdges(): Int =
        conn.prepareStatement(
            "SELECT COUNT(*) FROM edges e WHERE NOT EXISTS (SELECT 1 FROM artifacts a WHERE a.id = e.src_id) " +
                "AND NOT EXISTS (SELECT 1 FROM concepts c WHERE c.id = e.src_id)",
        ).use { st -> st.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
}
