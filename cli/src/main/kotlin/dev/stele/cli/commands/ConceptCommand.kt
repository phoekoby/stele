package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore

class ConceptCommand : CliktCommand(
    name = "concept",
    help = "Show a concept and the code that implements it (across languages/layers)",
) {
    private val name by argument(name = "name", help = "Concept name, e.g. Auth")

    override fun run() {
        val conn = openDb(requireDb().path)
        val store = GraphStore(conn)
        val concept = store.resolveConcept(name)
        if (concept == null) {
            conn.close()
            throw PrintMessage(
                "No concept '$name'. Run `stele ingest symbols` + `build-ontology` first.",
                statusCode = 1,
                printError = true,
            )
        }
        val impls = store.implementersOf(concept.id)
        val docs = store.describingDocs(concept.id)
        val related = store.relatedConcepts(concept.id)
        val rules = store.rulesFor(concept.id)
        conn.close()

        echo("concept: ${concept.name}  [${concept.status.value}]")
        concept.definition?.let { echo("  $it") }
        concept.boundedContext?.let { echo("  bounded context: $it") }
        if (concept.aliases.isNotEmpty()) echo("  aliases: ${concept.aliases.joinToString(", ")}")
        if (related.isNotEmpty()) echo("  related: ${related.take(10).joinToString(", ") { it.name }}")
        if (rules.isNotEmpty()) {
            echo("  rules (${rules.size}):")
            for (r in rules.take(6)) echo("    ‣ ${r.title}")
        }
        if (docs.isNotEmpty()) {
            echo("  described in ${docs.size} product docs:")
            for (d in docs.take(8)) echo("    • ${d.title}  (${d.ref})")
        }
        if (impls.isEmpty()) {
            echo("  (no linked code yet)")
            return
        }
        val byFile = impls.groupBy { it.ref.substringBefore('#') }
        echo("  implemented by ${impls.size} symbols across ${byFile.size} files:")
        for ((file, symbols) in byFile.entries.sortedBy { it.key }) {
            echo("    ${lang(file)} $file")
            for (s in symbols) echo("        • ${s.title}")
        }
    }
}

private fun lang(file: String): String = when {
    file.endsWith(".go") -> "[go]"
    file.endsWith(".tsx") || file.endsWith(".ts") -> "[ts]"
    file.endsWith(".kt") || file.endsWith(".kts") -> "[kt]"
    file.endsWith(".py") -> "[py]"
    else -> "[..]"
}
