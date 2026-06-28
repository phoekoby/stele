package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore

class StatsCommand : CliktCommand(name = "stats", help = "Show graph counts") {
    override fun run() {
        val db = requireDb()
        val conn = openDb(db.path)
        val store = GraphStore(conn)
        val c = store.counts()
        val byStatus = store.edgeStatusCounts()
        val byType = store.edgeTypeCounts()
        val indexed = store.fileMtimes().size
        val stale = db.parentFile?.parentFile?.let { store.staleFiles(it).size } ?: 0
        conn.close()

        val rows = listOf(
            "concepts" to c.concepts,
            "artifacts" to c.artifacts,
            "mentions" to c.mentions,
            "edges" to c.edges,
        )
        val width = rows.maxOf { it.first.length }
        for ((label, n) in rows) echo("  ${label.padEnd(width)}  $n")
        if (byType.isNotEmpty()) {
            echo("  edges by type:   ${byType.joinToString(", ") { "${it.first} ${it.second}" }}")
        }
        if (byStatus.isNotEmpty()) {
            echo("  edges by status: ${byStatus.joinToString(", ") { "${it.first} ${it.second}" }}")
        }
        if (indexed > 0) {
            echo("  indexed files:   $indexed" + if (stale > 0) " ($stale stale — re-run `stele ingest symbols`)" else " (all fresh)")
        }
    }
}
