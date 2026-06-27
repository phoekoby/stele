package dev.stele.connectors.codegraph

import kotlinx.serialization.json.Json
import java.io.File

/** A source of resolved code structure. GitNexus/SCIP exports plug in here. */
interface CodeGraphSource {
    fun load(): CodeGraphExport
}

private val json = Json { ignoreUnknownKeys = true }

/** Reads a code-graph export (our JSON schema) from disk. */
class JsonCodeGraphSource(private val path: String) : CodeGraphSource {
    override fun load(): CodeGraphExport = json.decodeFromString(File(path).readText())
}
