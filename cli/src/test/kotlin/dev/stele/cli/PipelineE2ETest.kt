package dev.stele.cli

import dev.stele.connectors.docs.ingestDocs
import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.extractors.ingestSymbols
import dev.stele.resolver.StaticLlmClient
import dev.stele.resolver.canonicalize
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The whole pipeline, wired end-to-end with a StaticLlmClient — the path a pilot's
 * "it returns nothing" bug would live in, and which had no test. Proves that
 * ingest symbols → canonicalize → ingest docs → ask actually connect: the answer
 * context carries the product RULE (docs side) and the real code SYMBOL (code side).
 */
class PipelineE2ETest {

    private fun fixtureRepo(dir: Path): File {
        val root = dir.toFile()
        File(root, "app/billing").mkdirs()
        File(root, "app/billing/charge.go").writeText(
            """
            package billing

            func CreateCharge() {
                // charge the customer
            }

            func RefundCharge() {
                // issue a refund
            }
            """.trimIndent(),
        )
        File(root, "docs").mkdirs()
        File(root, "docs/billing.md").writeText(
            "# Billing\n\nBilling charges the customer. A refund must be issued within 30 days of a completed charge.\n",
        )
        return root
    }

    @Test
    fun `ingest to canonicalize to docs to ask carries both the rule and the code`(@TempDir dir: Path) {
        val repo = fixtureRepo(dir)
        val conn = openDb(File(repo, ".stele/graph.db").also { it.parentFile.mkdirs() }.path)
        migrate(conn)
        val store = GraphStore(conn)

        // 1) code → candidate concept "Billing" (feature-folder cluster) + code symbols
        val sym = ingestSymbols(store, repo.path)
        assertTrue(sym.symbols >= 2, "parsed CreateCharge + RefundCharge")

        // 2) LLM canonicalizes the candidate (offline, canned verdict)
        val llm = StaticLlmClient(
            """[{"cluster":"Billing","keep":true,"name":"Billing","definition":"Plans, payments and charges.","confidence":0.9}]""",
        )
        val canon = canonicalize(store, llm, batchSize = 100)
        assertTrue(canon.kept >= 1, "Billing was canonicalized")

        // 3) docs attach to the canonical concept + extract the product rule
        val docs = ingestDocs(store, repo.path)
        assertTrue(docs.rules >= 1, "the refund rule was extracted")

        // 4) ask assembles the two-sided context (no LLM → context only)
        val res = AskService.ask(store, cfg = null, repoRoot = repo, question = "Billing", top = 1, llm = null)
        assertNotNull(res, "the question resolved to a concept")
        assertTrue(res.concepts.any { it.name == "Billing" }, "resolved to Billing")
        assertTrue(res.context.contains("refund must be issued within 30 days"), "DOCS SAY side carries the rule:\n${res.context}")
        assertTrue(res.context.contains("Charge"), "CODE DOES side carries a real symbol:\n${res.context}")
        conn.close()
    }
}
