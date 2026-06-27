package dev.stele.connectors.codegraph

import dev.stele.core.model.ArtifactKind
import dev.stele.core.model.ConceptStatus
import dev.stele.core.model.EdgeSource
import dev.stele.core.model.EdgeStatus
import dev.stele.core.model.EdgeType
import dev.stele.core.model.Layer
import dev.stele.core.store.GraphStore

data class CodeGraphIngestResult(
    val files: Int,
    val symbols: Int,
    val concepts: Int,
    val links: Int,
    val calls: Int,
)

/**
 * Ingest a resolved code graph onto the concept spine:
 *   community cluster  -> candidate concept
 *   member symbol      -> code_symbol artifact (file is canonical, source="code")
 *   membership         -> inferred `implements` edge (symbol -> concept), status=proposed
 *
 * The edge is `inferred`/`proposed` on purpose: clustering is a signal, not a
 * confirmed fact — it goes to the gate/human loop (Phase 5), not straight in.
 */
fun ingestCodeGraph(store: GraphStore, export: CodeGraphExport): CodeGraphIngestResult {
    val files = HashSet<String>()
    val symbols = HashSet<String>()
    var concepts = 0
    var links = 0

    for (community in export.communities) {
        val conceptId = store.findConceptId(community.label)
            ?: run { concepts++; store.addConcept(name = community.label, status = ConceptStatus.CANDIDATE) }

        for (member in community.members) {
            store.addArtifact(
                kind = ArtifactKind.FILE,
                layer = Layer.CODE,
                source = "code",
                ref = member.file,
                title = member.file,
            )
            files.add(member.file)

            val ref = "${member.file}#${member.name}"
            val symbolId = store.addArtifact(
                kind = ArtifactKind.CODE_SYMBOL,
                layer = Layer.CODE,
                source = "code",
                ref = ref,
                title = member.name,
                attrs = mapOf("symbolKind" to member.kind, "via" to export.source),
            )
            symbols.add(ref)

            store.addEdge(
                srcId = symbolId,
                dstId = conceptId,
                type = EdgeType.IMPLEMENTS,
                source = EdgeSource.INFERRED,
                confidence = 0.7,
                evidence = listOf("${export.source}:community:${community.label}"),
                status = EdgeStatus.PROPOSED,
            )
            links++
        }
    }

    // Resolved symbol↔symbol calls — facts from the indexer, so deterministic/confirmed.
    var callEdges = 0
    for (call in export.calls) {
        val fromId = store.addArtifact(
            kind = ArtifactKind.CODE_SYMBOL, layer = Layer.CODE, source = "code",
            ref = call.from, title = call.from.substringAfterLast('#'),
        )
        val toId = store.addArtifact(
            kind = ArtifactKind.CODE_SYMBOL, layer = Layer.CODE, source = "code",
            ref = call.to, title = call.to.substringAfterLast('#'),
        )
        store.addEdge(
            srcId = fromId, dstId = toId, type = EdgeType.CALLS, source = EdgeSource.DETERMINISTIC,
            confidence = 1.0, evidence = listOf("${export.source}:call"), status = EdgeStatus.CONFIRMED,
        )
        callEdges++
    }

    return CodeGraphIngestResult(files.size, symbols.size, concepts, links, callEdges)
}
