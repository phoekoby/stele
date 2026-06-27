package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.stele.cli.requireDb
import dev.stele.connectors.codegraph.AstIndexSource
import dev.stele.connectors.codegraph.JsonCodeGraphSource
import dev.stele.connectors.codegraph.ingestCodeGraph
import dev.stele.connectors.docs.ingestDocs
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.extractors.ingestCode
import dev.stele.extractors.ingestSymbols

/** `stele ingest` — parent group; the work lives in its subcommands. */
class IngestCommand : CliktCommand(name = "ingest", help = "Pull data from sources into the graph") {
    override fun run() = Unit
}

class IngestCodeCommand : CliktCommand(
    name = "code",
    help = "Parse a repo (any language) and extract code mentions",
) {
    private val path by argument(name = "path", help = "Path to the repo to ingest")

    override fun run() {
        val conn = openDb(requireDb().path)
        val res = ingestCode(GraphStore(conn), path)
        conn.close()

        echo("✓ ingested ${res.files} files, ${res.mentions} mentions")
        if (res.skippedLangs.isNotEmpty()) {
            echo("  skipped incompatible grammars: ${res.skippedLangs.joinToString(", ")}")
        }
    }
}

class IngestSymbolsCommand : CliktCommand(
    name = "symbols",
    help = "Index declarations (tree-sitter) and cluster them into candidate concepts",
) {
    private val path by argument(name = "path", help = "Path to the repo to index")

    override fun run() {
        val conn = openDb(requireDb().path)
        val res = ingestSymbols(GraphStore(conn), path)
        conn.close()
        echo(
            "✓ symbols: ${res.symbols} symbols, ${res.concepts} concepts, " +
                "${res.links} links across ${res.files} files",
        )
        if (res.skippedLangs.isNotEmpty()) {
            echo("  skipped grammars: ${res.skippedLangs.joinToString(", ")}")
        }
    }
}

class IngestDocsCommand : CliktCommand(
    name = "docs",
    help = "Ingest product docs (Markdown) and link sections to concepts they describe",
) {
    private val path by argument(name = "path", help = "Path to docs/repo to scan")

    override fun run() {
        val conn = openDb(requireDb().path)
        val res = ingestDocs(GraphStore(conn), path)
        conn.close()
        echo(
            "✓ docs: ${res.docs} docs, ${res.sections} sections, ${res.links} describes, " +
                "${res.aliasesAdded} aliases, ${res.relations} concept↔concept, ${res.rules} rules",
        )
    }
}

class IngestCodeGraphCommand : CliktCommand(
    name = "codegraph",
    help = "Ingest a resolved code graph (GitNexus/SCIP export): symbols + concept clusters",
) {
    private val export by argument(name = "export", help = "Path to the code-graph export JSON")

    override fun run() {
        val conn = openDb(requireDb().path)
        val data = JsonCodeGraphSource(export).load()
        val res = ingestCodeGraph(GraphStore(conn), data)
        conn.close()
        echo(
            "✓ codegraph: ${res.symbols} symbols, ${res.concepts} new concepts, " +
                "${res.links} links, ${res.calls} call edges across ${res.files} files",
        )
    }
}

class IngestAstIndexCommand : CliktCommand(
    name = "astindex",
    help = "Durable adapter: read an ast-index SQLite (index.db) directly — symbols + modules → concepts",
) {
    private val db by argument(name = "db", help = "Path to the ast-index SQLite database (index.db)")

    override fun run() {
        val conn = openDb(requireDb().path)
        val export = AstIndexSource(db).load()
        val res = ingestCodeGraph(GraphStore(conn), export)
        conn.close()
        echo(
            "✓ ast-index: ${res.symbols} symbols, ${res.concepts} new concepts, " +
                "${res.links} links across ${res.files} files",
        )
    }
}
