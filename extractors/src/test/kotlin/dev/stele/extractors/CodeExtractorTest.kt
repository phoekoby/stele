package dev.stele.extractors

import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class CodeExtractorTest {
    @Test
    fun ingestsKotlinAndPython() {
        val tmp = Files.createTempDirectory("stele-ingest").toFile()
        try {
            File(tmp, "Sample.kt").writeText(
                "fun validateRefund(amount: Int): Boolean { return amount > 0 } // refund check\n",
            )
            File(tmp, "sample.py").writeText(
                "def validate_refund(amount):\n    \"\"\"refund window\"\"\"\n    return amount > 0\n",
            )

            val conn = openDb(File(tmp, "graph.db").path)
            migrate(conn)
            val store = GraphStore(conn)

            val res = ingestCode(store, tmp.path)
            assertTrue(res.files >= 2, "expected >=2 files, got ${res.files}")
            assertTrue(res.mentions > 0, "expected mentions, got ${res.mentions}")
            assertTrue(store.counts().mentions > 0, "mentions should be persisted")

            val terms = store.topTerms(50).map { it.normalized }
            assertTrue(terms.any { it.contains("refund") }, "domain term 'refund' should surface: $terms")

            conn.close()
        } finally {
            tmp.deleteRecursively()
        }
    }
}
