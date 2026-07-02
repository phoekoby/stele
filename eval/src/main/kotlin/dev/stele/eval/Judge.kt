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
            Reply with a single integer 1-5:
            5 = agrees with the reference and covers its key facts
            3 = partially correct: some key facts, no contradictions
            1 = wrong, contradicts the reference, unsupported speculation, OR declines to
                answer ("I don't know") while the reference contains an answer.
            A refusal is never worth more than a partially correct attempt.
        """.trimIndent()
    }
}
