package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.mcp.formatCodeContext

class ExplainCommand : CliktCommand(
    name = "explain",
    help = "Product context for a file/path: concept(s) + rules + related + docs (the in-editor view)",
) {
    private val path by argument(name = "path", help = "Repo-relative file or directory path")

    override fun run() {
        val p = path.replace('\\', '/')
        val db = requireDb()
        val conn = openDb(db.path)
        val store = GraphStore(conn)
        val entries = store.codeContext(p)
        val (callsOut, callsIn) = store.callsForPath(p)
        val repoRoot = db.parentFile?.parentFile
        val stale = if (repoRoot != null && store.isStale(p, repoRoot)) setOf(p) else emptySet()
        conn.close()
        echo(formatCodeContext(p, entries, callsOut, callsIn, stale))
    }
}
