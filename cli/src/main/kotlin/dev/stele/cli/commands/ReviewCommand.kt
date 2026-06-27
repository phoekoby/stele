package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.model.EdgeStatus
import dev.stele.core.store.GraphStore

class ReviewCommand : CliktCommand(
    name = "review",
    help = "Human-in-the-loop: confirm/reject proposed edges. Confirmed = curated; rejected drops from serving.",
) {
    private val type by option("--type", help = "Edge type: implements | describes | relates | constrains")
    private val limit by option("--limit", help = "Max edges to review").int().default(40)
    private val acceptAbove by option(
        "--accept-above",
        help = "Non-interactive: confirm proposed edges with confidence >= this",
    ).double()

    override fun run() {
        val conn = openDb(requireDb().path)
        val store = GraphStore(conn)

        val above = acceptAbove
        if (above != null) {
            val n = store.confirmAbove(type, above)
            conn.close()
            echo("✓ confirmed $n proposed ${type ?: ""} edges (confidence ≥ $above)")
            return
        }

        val edges = store.proposedEdges(type, limit)
        if (edges.isEmpty()) {
            conn.close()
            echo("nothing to review")
            return
        }

        val reader = System.`in`.bufferedReader()
        var confirmed = 0
        var rejected = 0
        var skipped = 0
        for (e in edges) {
            echo("")
            echo("  ${e.src}")
            echo("     —${e.type}→  ${e.dst}   (${"%.2f".format(e.confidence)})")
            if (e.evidence.isNotEmpty()) echo("     evidence: ${e.evidence.joinToString("; ")}")
            echo("     confirm? [y]es / [n]o / [s]kip / [q]uit: ", trailingNewline = false)
            when (reader.readLine()?.trim()?.lowercase()) {
                "y", "yes" -> { store.setEdgeStatus(e.id, EdgeStatus.CONFIRMED); confirmed++ }
                "n", "no" -> { store.setEdgeStatus(e.id, EdgeStatus.REJECTED); rejected++ }
                "q", "quit" -> break
                else -> skipped++
            }
        }
        conn.close()
        echo("\n✓ review: $confirmed confirmed, $rejected rejected, $skipped skipped")
    }
}
