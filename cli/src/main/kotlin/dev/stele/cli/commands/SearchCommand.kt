package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore

class SearchCommand : CliktCommand(
    name = "search",
    help = "Find concepts by name, alias or definition (substring match)",
) {
    private val query by argument(name = "query", help = "Search term")
    private val limit by option("--limit", help = "Max results").int().default(20)

    override fun run() {
        val conn = openDb(requireDb().path)
        val hits = GraphStore(conn).searchConcepts(query, limit)
        conn.close()
        if (hits.isEmpty()) {
            echo("No concepts matching \"$query\". Try `stele build-ontology` first, or a broader term.")
            return
        }
        for (c in hits) {
            echo("• ${c.name}" + (c.boundedContext?.let { "  [$it]" } ?: ""))
            c.definition?.takeIf { it.isNotBlank() }?.let { echo("    $it") }
            if (c.aliases.isNotEmpty()) echo("    aka ${c.aliases.joinToString(", ")}")
        }
        echo("\n${hits.size} match${if (hits.size == 1) "" else "es"} — `stele concept <name>` for the code behind one.")
    }
}
