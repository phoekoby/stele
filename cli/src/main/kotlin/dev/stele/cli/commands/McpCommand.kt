package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import dev.stele.cli.EmbedderFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.dbFile
import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.core.store.UsageLog
import dev.stele.mcp.McpServer
import java.io.File
import java.io.OutputStreamWriter

class McpCommand : CliktCommand(
    name = "mcp",
    help = "Run the MCP server over stdio — serves concept_context / why_code / context_for_code to agents",
) {
    private val db by option("--db", help = "Path to the .stele graph (defaults to ./.stele/graph.db)")

    override fun run() {
        // stdout carries the JSON-RPC stream only — never echo here.
        // Resolve the graph by absolute path (set by `stele init`'s .mcp.json) so the server works no
        // matter what working directory the MCP client launches it from. Never abort on a missing graph:
        // create + migrate an empty one so `tools/list` always succeeds (queries then say "run ingest").
        val dbFileResolved = db?.let { File(it) } ?: dbFile()
        val fresh = !dbFileResolved.exists()
        dbFileResolved.parentFile?.mkdirs()
        val conn = openDb(dbFileResolved.path)
        if (fresh) migrate(conn)
        val usage = UsageLog(File(dbFileResolved.parentFile, "usage.jsonl"))
        // repo root = the dir that holds .stele, so the server can stat files for staleness.
        val repoRoot = dbFileResolved.parentFile?.parentFile
        // Semantic layer: same embedder config as `stele embed` wrote the vectors with.
        // stele.yml is looked up from the repo root (the client's cwd is arbitrary).
        val cfg = repoRoot?.let { root ->
            File(root, "stele.yml").takeIf { it.isFile }?.let { runCatching { ConfigLoader.load(it) }.getOrNull() }
        }
        val embedder = EmbedderFactory.fromConfig(cfg?.llm)
        McpServer(GraphStore(conn), usage, repoRoot, embedder).serve(
            System.`in`.bufferedReader(Charsets.UTF_8),
            OutputStreamWriter(System.out, Charsets.UTF_8),
        )
        conn.close()
    }
}
