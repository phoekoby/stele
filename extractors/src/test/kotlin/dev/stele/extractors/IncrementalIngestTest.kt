package dev.stele.extractors

import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class IncrementalIngestTest {

    @Test
    fun `re-index is incremental and tracks staleness`(@TempDir dir: Path) {
        val repo = dir.resolve("repo").toFile()
        File(repo, "auth").mkdirs()
        val f = File(repo, "auth/login.go")
        f.writeText("package auth\nfunc Login() {}\nfunc Logout() {}\n")
        f.setLastModified(1_000_000_000_000L)

        val stele = File(repo, ".stele").apply { mkdirs() }
        val conn = openDb(File(stele, "graph.db").path)
        migrate(conn)
        val store = GraphStore(conn)

        val r1 = ingestSymbols(store, repo.path)
        assertEquals(1, r1.changed)
        assertTrue(r1.symbols >= 2)
        assertFalse(store.isStale("auth/login.go", repo))

        // unchanged → nothing re-parsed
        assertEquals(0, ingestSymbols(store, repo.path).changed)

        // touch mtime → the file is stale until re-indexed
        f.setLastModified(2_000_000_000_000L)
        assertTrue(store.isStale("auth/login.go", repo))
        assertEquals(1, ingestSymbols(store, repo.path).changed)
        assertFalse(store.isStale("auth/login.go", repo))

        conn.close()
    }
}
