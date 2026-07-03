package dev.stele.extractors

import dev.stele.core.connector.refPrefix
import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MultiRepoTest {

    @Test
    fun `two repos share one graph without ref collisions or cross-reaping`(@TempDir dir: Path) {
        val ws = dir.toFile() // the workspace = graph root
        for (repo in listOf("core-api", "sdk")) {
            File(ws, "$repo/auth").mkdirs()
            // Same relative path in both repos — the collision case.
            File(ws, "$repo/auth/login.go").writeText("package auth\nfunc Login$repo() {}\n".replace("-", ""))
        }
        val conn = openDb(dir.resolve("graph.db").toString())
        migrate(conn)
        val store = GraphStore(conn)

        ingestSymbols(store, File(ws, "core-api").path, refPrefix(File(ws, "core-api").path, ws))
        ingestSymbols(store, File(ws, "sdk").path, refPrefix(File(ws, "sdk").path, ws))

        val refs = allRefs(conn)
        assertTrue("core-api/auth/login.go" in refs, "prefixed ref for repo A: $refs")
        assertTrue("sdk/auth/login.go" in refs, "prefixed ref for repo B: $refs")

        // Re-ingesting repo A must NOT reap repo B's files (they're not under A's prefix).
        ingestSymbols(store, File(ws, "core-api").path, refPrefix(File(ws, "core-api").path, ws))
        assertTrue("sdk/auth/login.go" in allRefs(conn), "repo B survived repo A's re-index")
        conn.close()
    }

    @Test
    fun `refPrefix rules`(@TempDir dir: Path) {
        val ws = dir.toFile()
        File(ws, "repoA").mkdirs()
        assertEquals("", refPrefix(ws.path, ws)) // single-repo: unchanged behavior
        assertEquals("repoA/", refPrefix(File(ws, "repoA").path, ws)) // workspace member
        assertEquals("elsewhere/", refPrefix("/tmp/elsewhere", ws)) // outside → dir name
    }

    private fun allRefs(conn: java.sql.Connection): Set<String> =
        conn.prepareStatement("SELECT path FROM source_files").use { st ->
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
}
