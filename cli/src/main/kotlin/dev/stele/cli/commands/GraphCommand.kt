package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.stele.cli.GraphHtml
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import java.io.File

/**
 * Export the concept graph as a single self-contained, offline HTML page —
 * an interactive force-directed map with search and a per-concept detail panel.
 * Lets a human eyeball the index quality (clusters, noise, unresolved leftovers,
 * proposed-vs-confirmed links) that the MCP serving slice hides.
 */
class GraphCommand : CliktCommand(
    name = "graph",
    help = "Export an interactive HTML view of the concept graph (open it in a browser)",
) {
    private val out by option("--out", help = "Output HTML file").default("stele-graph.html")
    private val resolvedOnly by option(
        "--resolved-only",
        help = "Only canonicalized concepts (drop unresolved candidates)",
    ).flag()

    override fun run() {
        val conn = openDb(requireDb().path)
        val (html, export) = GraphHtml.render(GraphStore(conn), resolvedOnly)
        conn.close()

        val file = File(out)
        file.writeText(html)
        echo("✓ graph: ${export.nodes.size} concepts, ${export.links.size} relations → ${file.path}")
        echo("  open: file:///${file.absolutePath.replace('\\', '/')}")
    }
}
