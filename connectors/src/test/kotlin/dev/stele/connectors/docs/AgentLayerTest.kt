package dev.stele.connectors.docs

import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.model.ConceptStatus
import dev.stele.core.store.GraphStore
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AgentLayerTest {

    private fun repoWith(store: GraphStore, dir: File) {
        val c = store.addConcept("Billing", status = ConceptStatus.CANDIDATE)
        store.updateConcept(c, "Billing", "Plans and payments", "Billing", emptyList())

        File(dir, ".agents/skills").mkdirs()
        File(dir, ".agents/skills/billing-playbook.md").writeText(
            "# Billing playbook\n\nWhen touching Billing code, check the Stripe webhook handler first.\n",
        )
        File(dir, "CLAUDE.md").writeText("# Project\n\n## Billing notes\n\nBilling must only charge via checkout.\n")
        File(dir, "docs").mkdirs()
        File(dir, "docs/billing.md").writeText("# Billing\n\nCustomers pay per seat.\n")
    }

    @Test
    fun `agent files land in the AGENT layer and never enrich the ontology`(@TempDir dir: Path) {
        val repo = dir.toFile()
        val conn = openDb(dir.resolve("graph.db").toString())
        migrate(conn)
        val store = GraphStore(conn)
        repoWith(store, repo)

        val agents = ingestAgents(store, repo.path)
        assertEquals(2, agents.docs) // the skill + CLAUDE.md
        assertTrue(agents.links > 0) // linked to Billing
        assertEquals(0, agents.aliasesAdded) // NO alias enrichment from agent headings
        assertEquals(0, agents.rules) // NO product rules from agent files
        assertEquals(listOf("agent"), layersOf(conn, "agents"))

        // ...while the docs connector skips agent files entirely.
        val docs = ingestDocs(store, repo.path)
        assertEquals(1, docs.docs) // only docs/billing.md — CLAUDE.md and .agents excluded
        conn.close()
    }

    @Test
    fun `alias gate drops step, imperative and long headings`() {
        assertNull(aliasFromHeading("Step 5: Update server-side authentication"))
        assertNull(aliasFromHeading("4 Auth endpoint rate limiting"))
        assertNull(aliasFromHeading("Verify login succeeded"))
        assertNull(aliasFromHeading("Session 1: Authentication flow"))
        assertNull(aliasFromHeading("What the Enterprise License Provides"))
        assertEquals("Direct Template Token", aliasFromHeading("Direct Template Token"))
        assertEquals("Live session", aliasFromHeading("Live session"))
    }

    private fun layersOf(conn: Connection, source: String): List<String> =
        conn.prepareStatement("SELECT DISTINCT layer FROM artifacts WHERE source = ?").use { st ->
            st.setString(1, source)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
}
