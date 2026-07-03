package dev.stele.resolver

/**
 * Drift detection — the "docs say vs code does" reconciliation, proactively.
 * A product rule (extracted from docs) plus the code slice that implements its
 * concept go to an LLM auditor; the verdict says whether the implementation
 * still matches the documented intent. VIOLATIONs are the product: silent
 * divergence between documentation and code that nobody tracks today.
 */
enum class DriftVerdict { VIOLATION, UNVERIFIABLE, COMPLIANT }

data class DriftFinding(
    val concept: String,
    val rule: String,
    val verdict: DriftVerdict,
    val reason: String,
    val files: List<String>,
)

class DriftChecker(private val llm: LlmClient) {

    fun check(concept: String, rule: String, codeSlice: String, files: List<String>): DriftFinding {
        if (codeSlice.isBlank()) {
            return DriftFinding(concept, rule, DriftVerdict.UNVERIFIABLE, "no code slice found for this rule", files)
        }
        val out = runCatching {
            llm.complete(
                SYSTEM,
                "CONCEPT: $concept\nPRODUCT RULE (from the docs): $rule\n\nIMPLEMENTATION SLICE:\n$codeSlice\n\nVerdict:",
            )
        }.getOrElse { return DriftFinding(concept, rule, DriftVerdict.UNVERIFIABLE, "LLM failed: ${it.message}", files) }
        return DriftFinding(concept, rule, parseVerdict(out), parseReason(out), files)
    }

    /** Lenient: find the verdict keyword anywhere; UNVERIFIABLE when the model rambles. */
    internal fun parseVerdict(out: String): DriftVerdict {
        val head = out.take(400).uppercase()
        return when {
            head.contains("VIOLAT") -> DriftVerdict.VIOLATION
            head.contains("COMPLIANT") || head.contains("COMPLIES") -> DriftVerdict.COMPLIANT
            else -> DriftVerdict.UNVERIFIABLE
        }
    }

    internal fun parseReason(out: String): String =
        out.lineSequence()
            .map { it.trim().removePrefix("REASON:").trim() }
            .filter { it.isNotBlank() && !it.uppercase().startsWith("VERDICT") }
            .firstOrNull()
            ?.take(300)
            ?: out.trim().take(300)

    companion object {
        private val SYSTEM = """
            You audit whether code complies with a documented product rule.
            Reply in exactly two lines:
            VERDICT: COMPLIANT | VIOLATION | UNVERIFIABLE
            REASON: one sentence citing the specific function or check that proves it.
            Judge conservatively: VIOLATION only when the shown code clearly contradicts the rule.
            If the slice lacks the evidence either way, answer UNVERIFIABLE — never guess.
        """.trimIndent()
    }
}
