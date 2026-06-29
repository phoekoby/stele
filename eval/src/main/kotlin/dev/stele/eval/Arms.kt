package dev.stele.eval

/** What an arm hands to the answering model: the concept(s) it resolved + the assembled context slice. */
data class Retrieved(
    /** Resolved concept names — scored against the gold concepts (resolution accuracy). */
    val concepts: List<String>,
    /** The text slice fed to the answering model — its size is the token cost we compare. */
    val context: String,
    /** Artifact refs/paths present in the context — scored against gold artifacts (recall). */
    val refs: List<String> = emptyList(),
)

/** One retrieval strategy under test. Resolution + context assembly only; answering is held constant. */
interface RetrievalArm {
    val name: String
    fun retrieve(question: String): Retrieved
}

/** Thrown by arms that are scaffolded but not yet built (e.g. [AgenticArm]). */
class ArmNotImplemented(armName: String) : RuntimeException("arm '$armName' not implemented yet")

/**
 * Baseline C — agentic grep. TODO(phase-0): drive a tool-use loop (glob/grep/read)
 * over the raw repo with the answering model and capture the context it pulls.
 */
class AgenticArm : RetrievalArm {
    override val name = "agentic"
    override fun retrieve(question: String): Retrieved = throw ArmNotImplemented(name)
}
