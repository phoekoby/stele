package dev.stele.extractors

import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SymbolSpanTest {

    @Test
    fun `ingest records 1-based line spans usable to pull symbol bodies`(@TempDir dir: Path) {
        val repo = dir.resolve("repo").toFile()
        File(repo, "auth").mkdirs()
        File(repo, "auth/login.go").writeText(
            """
            package auth

            func Login() {
                a := 1
                _ = a
            }
            """.trimIndent(),
        )

        val stele = File(repo, ".stele").apply { mkdirs() }
        val conn = openDb(File(stele, "graph.db").path)
        migrate(conn)
        val store = GraphStore(conn)
        ingestSymbols(store, repo.path)

        val id = symbolId(conn, "auth/login.go#Login")
        val span = store.symbolSpans(listOf(id))[id]
        assertEquals(3, span?.first) // `func Login()` is line 3
        assertTrue((span?.last ?: 0) >= 6) // body closes at line 6
        conn.close()
    }

    private fun symbolId(conn: Connection, ref: String): String =
        conn.prepareStatement("SELECT id FROM artifacts WHERE ref = ?").use { st ->
            st.setString(1, ref)
            st.executeQuery().use { rs -> rs.next(); rs.getString("id") }
        }
}
