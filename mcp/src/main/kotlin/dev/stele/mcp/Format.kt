package dev.stele.mcp

import dev.stele.core.model.CallEdge
import dev.stele.core.model.CodeContextEntry

private fun sym(ref: String): String = ref.substringAfterLast('#')

/** Renders `context_for_code` for both the MCP tool and the `stele explain` CLI. */
fun formatCodeContext(
    path: String,
    entries: List<CodeContextEntry>,
    callsOut: List<CallEdge> = emptyList(),
    callsIn: List<CallEdge> = emptyList(),
): String {
    if (entries.isEmpty() && callsOut.isEmpty() && callsIn.isEmpty()) {
        return "$path — no concept linked yet. Run `stele ingest symbols` + `build-ontology` + `ingest docs`."
    }
    return buildString {
        append("$path implements ${entries.size} concept(s):\n")
        for (e in entries) {
            append("\n▸ ${e.concept.name}")
            e.concept.boundedContext?.let { append("  [$it]") }
            append('\n')
            e.concept.definition?.let { append("  $it\n") }
            if (e.rules.isNotEmpty()) {
                append("  rules:\n")
                for (r in e.rules.take(5)) append("    ‣ ${r.title}\n")
            }
            if (e.related.isNotEmpty()) append("  related: ${e.related.take(8).joinToString(", ") { it.name }}\n")
            if (e.docs.isNotEmpty()) append("  docs: ${e.docs.take(4).joinToString(", ") { it.ref }}\n")
            append("  symbols here: ${e.symbols.take(20).joinToString(", ")}\n")
        }
        if (callsOut.isNotEmpty()) {
            append("\ncalls out (impact of changing this code):\n")
            for (c in callsOut.take(12)) append("  ${sym(c.fromRef)} → ${sym(c.toRef)}\n")
        }
        if (callsIn.isNotEmpty()) {
            append("\ncalled by (who breaks if you change it):\n")
            for (c in callsIn.take(12)) append("  ${sym(c.fromRef)} → ${sym(c.toRef)}\n")
        }
    }.trimEnd()
}
