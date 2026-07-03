package dev.stele.eval

import dev.stele.resolver.LlmClient
import java.io.File

/**
 * Baseline C — agentic grep: the SAME small model that answers also drives a
 * GREP/READ tool loop over the raw repo (what a coding agent does without any
 * index). The context handed to the answerer is what the loop managed to read;
 * [Retrieved.loopTokens] carries the tokens burned driving the loop — that cost
 * is exactly what the graph is supposed to save.
 */
class AgenticArm(
    private val repoRoot: File,
    private val llm: LlmClient,
    private val maxSteps: Int = 6,
) : RetrievalArm {
    override val name = "agentic"

    override fun retrieve(question: String): Retrieved {
        val notes = StringBuilder()
        val refs = LinkedHashSet<String>()
        var loopTokens = 0

        for (step in 1..maxSteps) {
            val user = buildString {
                append("Question: ").append(question).append("\n\n")
                if (notes.isNotEmpty()) append("Results so far:\n").append(notes.takeLast(4000)).append("\n\n")
                append("Reply with EXACTLY ONE command (nothing else):\n")
                append("GREP <term>   — search the repo for a term\n")
                append("READ <path>   — read a file you found\n")
                append("DONE          — you have enough context")
            }
            val reply = runCatching { llm.complete(SYSTEM, user).trim() }.getOrElse { return asRetrieved(notes, refs, loopTokens) }
            loopTokens += Metrics.approxTokens(SYSTEM) + Metrics.approxTokens(user) + Metrics.approxTokens(reply)

            val cmd = reply.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: break
            when {
                cmd.startsWith("DONE", ignoreCase = true) -> return asRetrieved(notes, refs, loopTokens)
                cmd.startsWith("GREP ", ignoreCase = true) -> {
                    val term = cmd.substring(5).trim().trim('"', '\'', '`')
                    notes.append("\n== grep \"$term\" ==\n").append(grep(term))
                }
                cmd.startsWith("READ ", ignoreCase = true) -> {
                    val path = cmd.substring(5).trim().trim('"', '\'', '`')
                    notes.append("\n== read $path ==\n").append(read(path, refs))
                }
                else -> notes.append("\n(unrecognised command: ${cmd.take(60)} — use GREP/READ/DONE)\n")
            }
        }
        return asRetrieved(notes, refs, loopTokens)
    }

    private fun asRetrieved(notes: StringBuilder, refs: Set<String>, loopTokens: Int): Retrieved =
        // Agentic search never names a concept either — compare it on recall/score/cost.
        Retrieved(emptyList(), notes.toString().trim().take(12_000), refs.toList(), loopTokens)

    private fun grep(term: String): String {
        if (term.length < 3) return "(term too short)\n"
        val needle = term.lowercase()
        val hits = StringBuilder()
        var count = 0
        outer@ for (file in RepoWalk.walk(repoRoot)) {
            val rel = repoRoot.toURI().relativize(file.toURI()).path
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            for ((i, line) in lines.withIndex()) {
                if (line.lowercase().contains(needle)) {
                    hits.append("$rel:${i + 1}: ${line.trim().take(160)}\n")
                    if (++count >= MAX_GREP_HITS) break@outer
                }
            }
        }
        return if (count == 0) "(no matches)\n" else hits.toString()
    }

    private fun read(path: String, refs: MutableSet<String>): String {
        val clean = path.substringBefore(':').trim() // tolerate "path:123" from grep output
        val file = File(repoRoot, clean)
        if (!file.isFile || file.length() > 1_000_000) return "(not a readable file: $clean)\n"
        refs.add(clean)
        val lines = runCatching { file.readLines() }.getOrNull() ?: return "(unreadable: $clean)\n"
        return lines.take(MAX_READ_LINES).joinToString("\n") + if (lines.size > MAX_READ_LINES) "\n… (truncated)" else ""
    }

    companion object {
        private const val MAX_GREP_HITS = 15
        private const val MAX_READ_LINES = 80

        private val SYSTEM = """
            You are gathering context from a code repository to answer a product question.
            You have three commands: GREP <term>, READ <path>, DONE.
            Search for the most SPECIFIC domain terms from the question, read the most
            promising files (docs and code), and reply DONE once you have enough.
            Reply with exactly one command per turn — no explanations.
        """.trimIndent()
    }
}
