package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore

class TermsCommand : CliktCommand(name = "terms", help = "Show the most common normalized terms") {
    override fun run() {
        val conn = openDb(requireDb().path)
        val terms = GraphStore(conn).topTerms(30)
        conn.close()

        if (terms.isEmpty()) {
            echo("  (no terms yet — run `stele ingest code <path>`)")
            return
        }
        val width = terms.maxOf { it.normalized.length }
        for (t in terms) echo("  ${t.normalized.padEnd(width)}  ${t.n}")
    }
}
