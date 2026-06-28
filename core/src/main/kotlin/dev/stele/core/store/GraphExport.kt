package dev.stele.core.store

/** A concept node for visualization, with its attached code/docs/rules counts and samples. */
data class GraphNode(
    val id: String,
    val name: String,
    val definition: String?,
    val boundedContext: String?,
    val aliases: List<String>,
    val status: String,
    val resolved: Boolean,
    val symbols: Int,
    val files: List<String>,
    val docs: List<String>,
    val rules: List<String>,
)

/** A concept↔concept `relates` edge, carrying its provenance status and confidence. */
data class GraphLink(
    val source: String,
    val target: String,
    val status: String,
    val confidence: Double,
)

data class GraphExport(val nodes: List<GraphNode>, val links: List<GraphLink>)
