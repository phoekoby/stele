package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphExport
import dev.stele.core.store.GraphStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        val full = GraphStore(conn).exportGraph()
        conn.close()
        val export = if (resolvedOnly) {
            val ids = full.nodes.filter { it.resolved }.map { it.id }.toSet()
            GraphExport(
                full.nodes.filter { it.id in ids },
                full.links.filter { it.source in ids && it.target in ids },
            )
        } else {
            full
        }

        val template = javaClass.getResourceAsStream("/graph/template.html")
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("graph template missing from the jar")
        val html = template.replace("__STELE_DATA__", toJson(export))

        val file = File(out)
        file.writeText(html)
        echo("✓ graph: ${export.nodes.size} concepts, ${export.links.size} relations → ${file.path}")
        echo("  open: file:///${file.absolutePath.replace('\\', '/')}")
    }

    private fun toJson(e: GraphExport): String {
        val obj = buildJsonObject {
            putJsonArray("nodes") {
                for (n in e.nodes) addJsonObject {
                    put("id", n.id)
                    put("name", n.name)
                    put("def", n.definition ?: "")
                    put("ctx", n.boundedContext ?: "")
                    put("status", n.status)
                    put("resolved", n.resolved)
                    put("symbols", n.symbols)
                    putJsonArray("aliases") { n.aliases.forEach { add(it) } }
                    putJsonArray("files") { n.files.forEach { add(it) } }
                    putJsonArray("docs") { n.docs.forEach { add(it) } }
                    putJsonArray("rules") { n.rules.forEach { add(it) } }
                }
            }
            putJsonArray("links") {
                for (l in e.links) addJsonObject {
                    put("source", l.source)
                    put("target", l.target)
                    put("status", l.status)
                    put("confidence", l.confidence)
                }
            }
        }
        return kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), obj)
    }
}
