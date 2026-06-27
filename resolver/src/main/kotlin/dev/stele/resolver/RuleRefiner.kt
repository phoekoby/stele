package dev.stele.resolver

import dev.stele.core.model.EdgeStatus
import dev.stele.core.model.RuleCandidate
import dev.stele.core.store.GraphStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One LLM verdict per candidate rule, referenced by its 1-based batch index. */
@Serializable
data class RuleVerdict(val id: Int, val keep: Boolean = false, val refined: String = "")

data class RefineResult(
    val processed: Int,
    val kept: Int,
    val dropped: Int,
    val rewritten: Int,
    val skipped: Int,
)

private val ruleJson = Json { ignoreUnknownKeys = true; isLenient = true }

private const val RULE_SYSTEM =
    "You curate PRODUCT RULES extracted from documentation for a concept graph. Each item is a candidate " +
        "sentence attached to a domain concept. KEEP it only if it is a genuine, code-enforceable product " +
        "invariant for that concept (e.g. \"completed documents cannot be deleted\", \"an API token is scoped " +
        "to one team\"). DROP noise: markdown-table fragments, code lines, headings, setup/build/deploy steps, " +
        "generic architecture statements not specific to the concept, vague or purely descriptive prose. For a " +
        "kept rule, rewrite it as ONE crisp imperative invariant (<= 18 words). Return ONLY a JSON array, one " +
        "object per item: {id (echo the number), keep (bool), refined (the rewrite, or empty if dropped)}."

/**
 * Turn the broad deterministic rule scrape into a curated set: an LLM judges each candidate `constrains`
 * edge — genuine product invariant vs doc noise — and rewrites the keepers crisply. Kept rules become
 * `confirmed` (so the serving gate shows them); the rest are `rejected`. No verdict for an item leaves it
 * `proposed` (re-run to finish). A total parse failure aborts with no changes. Local-first, batched.
 */
fun refineRules(store: GraphStore, llm: LlmClient, batchSize: Int = 12): RefineResult {
    val candidates = store.candidateRules()
    if (candidates.isEmpty()) return RefineResult(0, 0, 0, 0, 0)

    var kept = 0
    var dropped = 0
    var rewritten = 0
    var skipped = 0
    var anyVerdict = false

    for (chunk in candidates.chunked(batchSize)) {
        val verdicts = parseVerdicts(llm.complete(RULE_SYSTEM, buildRulePrompt(chunk))).associateBy { it.id }
        for ((i, cand) in chunk.withIndex()) {
            when (val v = verdicts[i + 1]) {
                null -> skipped++ // no verdict → leave it proposed, never change on a miss
                else -> {
                    anyVerdict = true
                    if (!v.keep) {
                        store.setEdgeStatus(cand.edgeId, EdgeStatus.REJECTED)
                        dropped++
                    } else {
                        store.setEdgeStatus(cand.edgeId, EdgeStatus.CONFIRMED)
                        val refined = v.refined.trim()
                        if (refined.isNotEmpty() && refined != cand.text.trim()) {
                            store.updateArtifactTitle(cand.ruleId, refined.take(200))
                            rewritten++
                        }
                        kept++
                    }
                }
            }
        }
    }
    if (!anyVerdict) throw RuntimeException("LLM returned no parseable verdicts — aborting, no rules changed.")
    return RefineResult(candidates.size, kept, dropped, rewritten, skipped)
}

private fun buildRulePrompt(chunk: List<RuleCandidate>): String = buildString {
    append("Candidate rules:\n")
    for ((i, c) in chunk.withIndex()) {
        append(i + 1).append(". [concept: ").append(c.concept).append("] ")
            .append(c.text.replace('\n', ' ').take(220)).append('\n')
    }
    append("\nReturn a JSON array of {id, keep, refined}, one per item above.")
}

/** Extract the JSON array even if the model wrapped it in prose/markdown. */
private fun parseVerdicts(raw: String): List<RuleVerdict> {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    return runCatching { ruleJson.decodeFromString<List<RuleVerdict>>(raw.substring(start, end + 1)) }
        .getOrDefault(emptyList())
}
