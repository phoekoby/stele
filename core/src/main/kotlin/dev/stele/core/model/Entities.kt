package dev.stele.core.model

/** Graph node/edge domain models and small query results. */

data class Concept(
    val id: String,
    val name: String,
    val definition: String? = null,
    val boundedContext: String? = null,
    val aliases: List<String> = emptyList(),
    val status: ConceptStatus = ConceptStatus.CANDIDATE,
)

data class Artifact(
    val id: String,
    val kind: ArtifactKind,
    val layer: Layer,
    val source: String,
    val ref: String,
    val title: String? = null,
    val body: String? = null,
    val attrs: Map<String, Any?> = emptyMap(),
)

data class Mention(
    val id: String,
    val artifactId: String,
    val term: String,
    val normalized: String,
    val span: String? = null,
)

data class Edge(
    val id: String,
    val srcId: String,
    val dstId: String,
    val type: EdgeType,
    val source: EdgeSource,
    val confidence: Double = 1.0,
    val evidence: List<String> = emptyList(),
    val status: EdgeStatus = EdgeStatus.PROPOSED,
)

data class GraphCounts(
    val concepts: Int,
    val artifacts: Int,
    val mentions: Int,
    val edges: Int,
)

data class TermCount(val normalized: String, val n: Int)

data class DedupeResult(val merged: Int, val groups: Int)

/** The full knowledge a file/path touches — concept + rules + related + docs + the symbols here. */
data class CodeContextEntry(
    val concept: Concept,
    val symbols: List<String>,
    val rules: List<Artifact>,
    val related: List<Concept>,
    val docs: List<Artifact>,
)

/** A resolved `calls` edge between two code symbols (refs are `file#symbol`). */
data class CallEdge(val fromRef: String, val toRef: String)

/** A scraped product-rule candidate (one `constrains` edge) awaiting LLM refinement. */
data class RuleCandidate(
    val edgeId: String,
    val ruleId: String,
    val text: String,
    val conceptId: String,
    val concept: String,
)

/** A proposed edge surfaced for human review (Phase 5), with resolved endpoint labels. */
data class EdgeView(
    val id: String,
    val type: String,
    val src: String,
    val dst: String,
    val confidence: Double,
    val evidence: List<String>,
    val status: String,
)

/** A candidate concept plus sample evidence, fed to the canonicalizer (Phase 3). */
data class ConceptCandidate(
    val id: String,
    val name: String,
    val symbols: List<String>,
    val dirs: List<String>,
)
