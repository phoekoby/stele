package dev.stele.resolver

import dev.stele.core.model.ConceptCandidate
import dev.stele.core.store.GraphStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One LLM verdict per candidate cluster. */
@Serializable
data class Canonicalization(
    val cluster: String,
    val keep: Boolean = true,
    val name: String = "",
    val definition: String = "",
    @SerialName("bounded_context") val boundedContext: String = "",
    val aliases: List<String> = emptyList(),
    val confidence: Double = 0.5,
)

data class CanonicalizeResult(
    val processed: Int,
    val kept: Int,
    val dropped: Int,
    val renamed: Int,
    val skipped: Int,
)

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private const val SYSTEM =
    "You are a domain-modeling assistant building a ubiquitous-language ontology from a codebase. " +
        "You receive clusters of code symbols (grouped by folder). For each cluster decide whether it is a genuine " +
        "PRODUCT / DOMAIN concept (e.g. Auth, Billing, Subscription, Secrets) versus infrastructure / tooling / " +
        "framework / file-name noise (e.g. Psql, Proxy, Console, Client, Config, Server, Misc). For real concepts give " +
        "a canonical Name (PascalCase noun), a SHORT one-sentence definition (max ~14 words), a bounded_context " +
        "(the subdomain), and aliases. Return ONLY a JSON array, one object per input cluster, no prose."

/**
 * Phase 3: turn candidate concepts (folder clusters) into real ones. Sends the
 * candidates + sample symbols to [llm] in small batches; for each verdict either
 * fills name/definition/bounded_context (kept) or drops the concept (noise / low
 * confidence). Concepts stay `candidate` — human confirmation is Phase 5.
 * Batching keeps each call short — local models choke on one giant request.
 */
fun canonicalize(
    store: GraphStore,
    llm: LlmClient,
    gate: Double = 0.5,
    batchSize: Int = 8,
): CanonicalizeResult {
    val candidates = store.candidateConcepts()
    if (candidates.isEmpty()) return CanonicalizeResult(0, 0, 0, 0, 0)

    var kept = 0
    var dropped = 0
    var renamed = 0
    var skipped = 0
    var anyParsed = false

    for (chunk in candidates.chunked(batchSize)) {
        val parsed = parseArray(llm.complete(SYSTEM, buildPrompt(chunk)))
        if (parsed.isNotEmpty()) anyParsed = true
        // Match a verdict to its cluster case/whitespace-insensitively (an 8B often echoes
        // the cluster name with different casing/spacing, which silently dropped the concept).
        val byNorm = HashMap<String, Canonicalization>()
        for (p in parsed) byNorm.putIfAbsent(norm(p.cluster), p)
        val chunkNorms = chunk.map { norm(it.name) }.toSet()

        for ((i, c) in chunk.withIndex()) {
            var v = byNorm[norm(c.name)]
            // Positional fallback: if the model returned one object per candidate but reworded
            // the cluster field, pair by position — but only with an "orphan" object that matches
            // no candidate by name, so we never mispair onto the wrong concept.
            if (v == null && parsed.size == chunk.size && norm(parsed[i].cluster) !in chunkNorms) {
                v = parsed[i]
            }
            when {
                v == null -> skipped++ // no verdict → leave it candidate, never delete on a miss
                !v.keep || v.confidence < gate -> { store.deleteConcept(c.id); dropped++ }
                else -> {
                    val name = v.name.ifBlank { c.name }
                    if (name != c.name) renamed++
                    // A rename must NEVER lose the original cluster name — it's the term the
                    // code (folder) and possibly the team actually use; keep it as an alias
                    // so lexical + semantic resolution keep the bridge.
                    val aliases = if (name != c.name) (v.aliases + c.name).distinct() else v.aliases
                    store.updateConcept(c.id, name, v.definition.ifBlank { null }, v.boundedContext.ifBlank { null }, aliases)
                    kept++
                }
            }
        }
    }
    // Safety: a total parse/LLM failure must not silently no-op the whole spine.
    if (!anyParsed) {
        throw RuntimeException("LLM returned no parseable verdicts — aborting, no concepts changed.")
    }
    return CanonicalizeResult(candidates.size, kept, dropped, renamed, skipped)
}

/** Normalize a cluster/verdict key: trim + lowercase, so casing/spacing drift doesn't drop the match. */
private fun norm(s: String): String = s.trim().lowercase()

private fun buildPrompt(candidates: List<ConceptCandidate>): String = buildString {
    append("Clusters:\n")
    for (c in candidates) {
        append("- cluster: ").append(c.name).append('\n')
        append("  symbols: ").append(c.symbols.take(12).joinToString(", ")).append('\n')
        append("  dirs: ").append(c.dirs.joinToString(", ")).append('\n')
    }
    append("\nReturn a JSON array; one object per cluster with fields: ")
    append("cluster (echo the input cluster), keep (bool), name, definition, bounded_context, aliases (array), confidence (0..1).")
}

/** Extract the JSON array even if the model wrapped it in prose/markdown. */
private fun parseArray(raw: String): List<Canonicalization> {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    return runCatching { json.decodeFromString<List<Canonicalization>>(raw.substring(start, end + 1)) }
        .getOrDefault(emptyList())
}
