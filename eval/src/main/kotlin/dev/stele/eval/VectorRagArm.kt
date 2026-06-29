package dev.stele.eval

import java.io.File

/**
 * Baseline B — naive vector RAG, the thing Stele is measured against. Chunk every
 * text/code file in the repo, embed each chunk, and at query time return the top-k
 * chunks by cosine similarity, concatenated. NO concept layer, NO graph traversal —
 * just "embed everything, retrieve nearest." Uses the SAME answering model as
 * [SteleArm] downstream, so the only thing that differs is the retrieval.
 *
 * The index is built once, lazily, on the first [retrieve] (so an unselected arm costs
 * nothing). Default [Embedder] is the offline [HashingEmbedder]; pass an [OllamaEmbedder]
 * for real semantic vectors.
 */
class VectorRagArm(
    private val repoRoot: File,
    private val embedder: Embedder = HashingEmbedder(),
    private val topK: Int = 6,
    private val chunkLines: Int = 50,
    private val maxFileBytes: Long = 1_000_000,
) : RetrievalArm {
    override val name = "vector"

    private class Chunk(val ref: String, val text: String, val vec: FloatArray)

    private val index: List<Chunk> by lazy { buildIndex() }

    override fun retrieve(question: String): Retrieved {
        if (index.isEmpty()) return Retrieved(emptyList(), "")
        val q = embedder.embed(question)
        val top = index
            .map { it to cosine(q, it.vec) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }

        val refs = top.map { it.ref }.distinct()
        val context = buildString {
            for (c in top) {
                append("// ${c.ref}\n")
                append(c.text.trim()).append("\n\n")
            }
        }.trim()
        // Vector RAG has no concept resolution — it retrieves raw text, never names a concept.
        return Retrieved(emptyList(), context, refs)
    }

    private fun buildIndex(): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        for (file in walk(repoRoot)) {
            val rel = repoRoot.toURI().relativize(file.toURI()).path
            val lines = runCatching { file.readText() }.getOrNull()?.lines() ?: continue
            var i = 0
            while (i < lines.size) {
                val slice = lines.subList(i, minOf(i + chunkLines, lines.size))
                val text = slice.joinToString("\n")
                if (text.isNotBlank()) {
                    val ref = if (lines.size > chunkLines) "$rel#L${i + 1}" else rel
                    chunks += Chunk(ref, text, embedder.embed(text))
                }
                i += chunkLines
            }
        }
        return chunks
    }

    private fun walk(dir: File): Sequence<File> = dir.walkTopDown()
        .onEnter { it.name !in IGNORE_DIRS }
        .filter { it.isFile && it.length() in 1..maxFileBytes && it.extension.lowercase() in TEXT_EXT }

    companion object {
        private val IGNORE_DIRS = setOf(
            "node_modules", ".git", "dist", "build", ".stele", ".next", "out", "target",
            "vendor", "__pycache__", ".venv", ".idea", ".gradle",
        )
        private val TEXT_EXT = setOf(
            "kt", "kts", "java", "go", "ts", "tsx", "js", "jsx", "py", "rb", "rs", "c", "cc",
            "cpp", "h", "hpp", "cs", "swift", "dart", "scala", "php", "sql", "md", "mdx",
            "txt", "yml", "yaml", "json", "toml", "proto", "gradle",
        )
    }
}
