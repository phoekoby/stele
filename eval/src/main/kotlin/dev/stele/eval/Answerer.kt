package dev.stele.eval

import dev.stele.resolver.LlmClient

/**
 * The held-constant "support model": answers the question using ONLY the retrieved
 * context. Same model for every arm — so any quality difference is the retrieval's,
 * not the model's. Kept deliberately small/cheap in practice (Haiku / 8B).
 */
class Answerer(private val llm: LlmClient) {
    fun answer(question: String, context: String): String {
        if (context.isBlank()) return NO_CONTEXT
        return llm.complete(SYSTEM, "Context:\n$context\n\nQuestion: $question").trim()
    }

    companion object {
        const val NO_CONTEXT = "I don't know from the available context."

        private val SYSTEM = """
            You are a support assistant answering questions about a software product for non-engineers.
            Answer ONLY from the provided context, and cite the file or doc you used.
            If the context does not contain the answer, reply exactly: I don't know from the available context.
            Be concise (2-5 sentences).
        """.trimIndent()
    }
}
