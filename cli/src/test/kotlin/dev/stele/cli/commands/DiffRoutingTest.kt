package dev.stele.cli.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffRoutingTest {

    private val diff = """
        diff --git a/packages/lib/server-only/document/delete-document.ts b/packages/lib/server-only/document/delete-document.ts
        index 111..222 100644
        --- a/packages/lib/server-only/document/delete-document.ts
        +++ b/packages/lib/server-only/document/delete-document.ts
        @@ -10,3 +10,7 @@ export const x = 1;
         const keep = true;
        -const removed = 1;
        +export const forceDeleteDocument = async (id: number) => {
        +  return await prisma.envelope.delete({ where: { id } });
        +};
        diff --git a/README.md b/README.md
        deleted file mode 100644
        --- a/README.md
        +++ /dev/null
        @@ -1,2 +0,0 @@
        -gone
    """.trimIndent()

    @Test
    fun `parseDiff extracts changed files, hunks and ADDED lines only`() {
        val files = parseDiff(diff)
        // the deleted file (+++ /dev/null) is not a routable target
        assertEquals(listOf("packages/lib/server-only/document/delete-document.ts"), files.map { it.path })
        val f = files.single()
        assertTrue(f.added.contains("forceDeleteDocument"), "added lines captured")
        assertTrue(!f.added.contains("const removed"), "removed lines are not 'added'")
        assertTrue(f.hunks.contains("-const removed = 1;"), "hunks keep full context for the auditor")
    }

    @Test
    fun `stem collapses the prose-vs-code inflections that matter for routing`() {
        // "Completed documents cannot be deleted." must overlap "forceDeleteDocument"
        assertEquals(stem("documents"), stem("document"))
        assertEquals(stem("deleted"), stem("delete"))
        assertEquals(stem("completed"), stem("complete"))
        assertTrue(stem("team") == "team", "short tokens stay exact")
    }
}
