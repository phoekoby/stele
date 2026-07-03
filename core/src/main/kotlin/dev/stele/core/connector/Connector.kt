package dev.stele.core.connector

import dev.stele.core.store.GraphStore
import java.io.File

/** When a source runs in the pipeline: code sources seed concepts; doc sources attach to them. */
enum class ConnectorPhase { CODE, DOC }

/**
 * Multi-repo: the ref prefix for a source path. Refs are stored relative to the
 * GRAPH root (the dir holding `.stele`), so several repos can feed one graph
 * without path collisions and staleness/body-reads resolve via the graph root:
 *  - source path == graph root (the single-repo case) → "" (unchanged behavior)
 *  - source under the graph root (workspace layout)   → "repoA/"
 *  - source elsewhere                                  → "<dirname>/"
 */
fun refPrefix(pathArg: String, graphRoot: File = File(System.getProperty("user.dir"))): String {
    val root = File(pathArg).absoluteFile.normalize()
    val cwd = graphRoot.absoluteFile.normalize()
    if (root == cwd) return ""
    val rel = runCatching { cwd.toPath().relativize(root.toPath()).toString().replace('\\', '/') }.getOrNull()
    return if (rel != null && rel.isNotBlank() && !rel.startsWith("..")) "$rel/" else "${root.name}/"
}

/** Resolved settings for one configured source (a `stele.yml` entry). Connectors read what they need. */
data class ConnectorParams(
    val path: String? = null,
    val urls: List<String> = emptyList(),
)

/**
 * A data source that ingests into the graph. This is the extension seam: add a
 * new source (Notion, Slack, an API…) by implementing this and registering it —
 * no change to the pipeline. `ingest` returns a one-line summary for the CLI.
 */
interface Connector {
    val type: String
    val help: String
    val phase: ConnectorPhase get() = ConnectorPhase.CODE
    fun ingest(store: GraphStore, params: ConnectorParams): String
}
