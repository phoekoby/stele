package dev.stele.eval

/**
 * Runs each arm over the golden set, scores it, and returns a per-arm report plus the
 * raw per-question results. Concept-resolution accuracy + token cost are computed with
 * no model at all; answering/judging only kick in when an [Answerer]/[Judge] is supplied.
 * With [samples] > 1 each question is answered+judged K times (retrieval is
 * deterministic and runs once) — the variance that decides whether an answer-score
 * delta is real or judge noise.
 */
class EvalRunner(
    private val golden: GoldenSet,
    private val answerer: Answerer? = null,
    private val judge: Judge? = null,
    private val samples: Int = 1,
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
            var firstAnswer: String? = null
            val scores = mutableListOf<Int>()
            if (answerer != null) {
                repeat(samples) {
                    val ans = answerer.answer(q.question, retrieved.context)
                    if (firstAnswer == null) firstAnswer = ans
                    judge?.score(q.question, q.answer, ans)?.let { scores.add(it) }
                }
            }
            val latency = System.currentTimeMillis() - start
            results += QResult(
                questionId = q.id,
                resolvedConcepts = retrieved.concepts,
                conceptHit = Metrics.conceptHit(retrieved.concepts, q.concepts),
                artifactRecall = Metrics.artifactRecall(retrieved.refs, q.artifacts),
                approxTokens = Metrics.approxTokens(retrieved.context) + retrieved.loopTokens,
                latencyMs = latency,
                answer = firstAnswer,
                scores = scores,
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
        val score = r.meanScore?.let { m ->
            "%.2f".format(m) + (r.scoreCi95?.let { " ±%.2f".format(it) } ?: "") + " / 5"
        } ?: "n/a"
        sink(
            "%-14s %3d   %9.1f%%   %15s   %7.0f   %10.0f   %s".format(
                r.arm,
                r.n,
                r.conceptHitRate * 100,
                if (r.meanArtifactRecall.isNaN()) "n/a" else "%.1f%%".format(r.meanArtifactRecall * 100),
                r.meanTokens,
                r.meanLatencyMs,
                score,
            ),
        )
    }
}

/** Renders pairwise sign tests over per-question mean scores (needs --answer). */
fun renderPaired(results: List<Pair<ArmReport, List<QResult>>>, sink: (String) -> Unit) {
    val judged = results.filter { (r, qs) -> r.implemented && qs.any { it.meanScore != null } }
    if (judged.size < 2) return
    sink("")
    sink("paired (per-question mean scores, exact sign test):")
    for (i in judged.indices) for (j in i + 1 until judged.size) {
        val (ra, qa) = judged[i]
        val (rb, qb) = judged[j]
        val p = Metrics.paired(ra, qa, rb, qb) ?: continue
        sink(
            "  %-11s vs %-11s  %dW/%dL/%dT   p=%.3f%s".format(
                p.armA, p.armB, p.wins, p.losses, p.ties, p.p,
                if (p.p < 0.05) "  *significant*" else "",
            ),
        )
    }
}
