package dev.stele.core.connector

import dev.stele.core.store.GraphStore

/** When a source runs in the pipeline: code sources seed concepts; doc sources attach to them. */
enum class ConnectorPhase { CODE, DOC }

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
