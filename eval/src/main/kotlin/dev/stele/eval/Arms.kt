package dev.stele.eval

import java.io.File

/** What an arm hands to the answering model: the concept(s) it resolved + the assembled context slice. */
data class Retrieved(
    /** Resolved concept names — scored against the gold concepts (resolution accuracy). */
    val concepts: List<String>,
    /** The text slice fed to the answering model — its size is the token cost we compare. */
    val context: String,
    /** Artifact refs/paths present in the context — scored against gold artifacts (recall). */
    val refs: List<String> = emptyList(),
    /** Extra tokens the arm BURNED gathering context (agentic tool loop) — part of its cost. */
    val loopTokens: Int = 0,
)

/** One retrieval strategy under test. Resolution + context assembly only; answering is held constant. */
interface RetrievalArm {
    val name: String
    fun retrieve(question: String): Retrieved
}

/** Thrown by arms that can't run in the current configuration. */
class ArmNotImplemented(armName: String) : RuntimeException("arm '$armName' not available in this configuration")

/** Repo walking shared by the arms that read raw files (vector chunks, agentic grep/read). */
internal object RepoWalk {
    val IGNORE_DIRS = setOf(
        "node_modules", ".git", "dist", "build", ".stele", ".next", "out", "target",
        "vendor", "__pycache__", ".venv", ".idea", ".gradle",
    )
    val TEXT_EXT = setOf(
        "kt", "kts", "java", "go", "ts", "tsx", "js", "jsx", "py", "rb", "rs", "c", "cc",
        "cpp", "h", "hpp", "cs", "swift", "dart", "scala", "php", "sql", "md", "mdx",
        "txt", "yml", "yaml", "json", "toml", "proto", "gradle",
    )

    fun walk(dir: File, maxFileBytes: Long = 1_000_000): Sequence<File> = dir.walkTopDown()
        .onEnter { it.name !in IGNORE_DIRS }
        .filter { it.isFile && it.length() in 1..maxFileBytes && it.extension.lowercase() in TEXT_EXT }
}
