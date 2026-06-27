package dev.stele.connectors.codegraph

import kotlinx.serialization.Serializable

/**
 * Our vendor-neutral code-graph export schema. One producer today is GitNexus
 * (communities + resolved symbols); SCIP can be another adapter later.
 */
@Serializable
data class CodeGraphExport(
    val repo: String? = null,
    /** Provenance tag for the inferred edges (e.g. "gitnexus"). */
    val source: String = "codegraph",
    val communities: List<CommunityExport> = emptyList(),
    /** Resolved symbol↔symbol call edges from the indexer (file#name → file#name). */
    val calls: List<CallExport> = emptyList(),
)

/** A resolved call edge: `from` calls `to`, each a `file#name` symbol ref. */
@Serializable
data class CallExport(val from: String, val to: String)

/** A cluster of related code — a candidate concept. */
@Serializable
data class CommunityExport(
    val label: String,
    val members: List<SymbolExport> = emptyList(),
)

/** A resolved definition node (function/type/method/…). */
@Serializable
data class SymbolExport(
    val kind: String,
    val name: String,
    val file: String,
)
