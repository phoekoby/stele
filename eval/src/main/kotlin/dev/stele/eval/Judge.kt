package dev.stele.eval

import dev.stele.resolver.LlmClient

/** LLM-as-judge: scores a candidate answer 1-5 against the reference answer. */
class Judge(private val llm: LlmClient) {
    /** Score in 1..5, or null when there's no reference or the verdict is unparseable. */
    fun score(question: String, reference: String?, candidate: String): Int? {
        if (reference.isNullOrBlank()) return null
        val out = llm.complete(
            SYSTEM,
            "Question: $question\nReference answer: $reference\nCandidate answer: $candidate\n\nScore (1-5):",
        )
        return Regex("[1-5]").find(out)?.value?.toIntOrNull()
    }

    companion object {
        private val SYSTEM = """
            You grade a candidate answer against a reference answer for factual agreement and completeness.
            Reply with a single integer 1-5: 5 = fully correct and complete, 1 = wrong or unsupported.
        """.trimIndent()
    }
}
