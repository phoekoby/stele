package dev.stele.eval

/**
 * Runs each arm over the golden set, scores it, and returns a per-arm report plus the
 * raw per-question results. Concept-resolution accuracy + token cost are computed with
 * no model at all; answering/judging only kick in when an [Answerer]/[Judge] is supplied.
 */
class EvalRunner(
    private val golden: GoldenSet,
    private val answerer: Answerer? = null,
    private val judge: Judge? = null,
) {
    fun run(arms: List<RetrievalArm>): List<Pair<ArmReport, List<QResult>>> = arms.map { evalArm(it) }

    private fun evalArm(arm: RetrievalArm): Pair<ArmReport, List<QResult>> {
        val results = mutableListOf<QResult>()
        for (q in golden.questions) {
            val start = System.currentTimeMillis()
            val retrieved = try {
                arm.retrieve(q.question)
            } catch (e: ArmNotImplemented) {
                return ArmReport(arm.name, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, implemented = false) to emptyList()
            }
            val ans = answerer?.answer(q.question, retrieved.context)
            val latency = System.currentTimeMillis() - start
            val score = if (ans != null && judge != null) judge.score(q.question, q.answer, ans) else null
            results += QResult(
                questionId = q.id,
                resolvedConcepts = retrieved.concepts,
                conceptHit = Metrics.conceptHit(retrieved.concepts, q.concepts),
                artifactRecall = Metrics.artifactRecall(retrieved.refs, q.artifacts),
                approxTokens = Metrics.approxTokens(retrieved.context),
                latencyMs = latency,
                answer = ans,
                score = score,
            )
        }
        return Metrics.aggregate(arm.name, results) to results
    }
}

/** Renders the arm comparison as a fixed-width table to a sink (e.g. CLI echo). */
fun renderReport(reports: List<ArmReport>, sink: (String) -> Unit) {
    sink("arm            n    concept-hit   artifact-recall   ~tokens   latency(ms)   answer-score")
    sink("-".repeat(92))
    for (r in reports) {
        if (!r.implemented) {
            sink("%-14s n/a — not implemented yet".format(r.arm))
            continue
        }
        sink(
            "%-14s %3d   %9.1f%%   %15s   %7.0f   %10.0f   %s".format(
                r.arm,
                r.n,
                r.conceptHitRate * 100,
                if (r.meanArtifactRecall.isNaN()) "n/a" else "%.1f%%".format(r.meanArtifactRecall * 100),
                r.meanTokens,
                r.meanLatencyMs,
                r.meanScore?.let { "%.2f / 5".format(it) } ?: "n/a",
            ),
        )
    }
}
