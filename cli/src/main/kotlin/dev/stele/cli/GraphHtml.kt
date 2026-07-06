package dev.stele.cli

import dev.stele.core.store.GraphExport
import dev.stele.core.store.GraphStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Renders the interactive graph viewer HTML — shared by `stele graph` (static file) and `stele serve` (live). */
object GraphHtml {

    fun render(store: GraphStore, resolvedOnly: Boolean): Pair<String, GraphExport> {
        val full = store.exportGraph()
        val export = if (resolvedOnly) {
            val ids = full.nodes.filter { it.resolved }.map { it.id }.toSet()
            GraphExport(
                full.nodes.filter { it.id in ids },
                full.links.filter { it.source in ids && it.target in ids },
            )
        } else {
            full
        }
        val template = javaClass.getResourceAsStream("/graph/template.html")
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("graph template missing from the jar")
        return template.replace("__STELE_DATA__", toJson(export)) to export
    }

    private fun toJson(e: GraphExport): String {
        val obj = buildJsonObject {
            putJsonArray("nodes") {
                for (n in e.nodes) addJsonObject {
                    put("id", n.id)
                    put("name", n.name)
                    put("def", n.definition ?: "")
                    put("ctx", n.boundedContext ?: "")
                    put("status", n.status)
                    put("resolved", n.resolved)
                    put("symbols", n.symbols)
                    putJsonArray("aliases") { n.aliases.forEach { add(it) } }
                    putJsonArray("files") { n.files.forEach { add(it) } }
                    putJsonArray("docs") { n.docs.forEach { add(it) } }
                    putJsonArray("rules") { n.rules.forEach { add(it) } }
                }
            }
            putJsonArray("links") {
                for (l in e.links) addJsonObject {
                    put("source", l.source)
                    put("target", l.target)
                    put("status", l.status)
                    put("confidence", l.confidence)
                }
            }
        }
        // Escape HTML-significant chars so a concept name/def/rule containing `</script>`
        // can't break out of the inlined <script> tag (stored XSS). < etc. parse
        // back to the same string in JSON.parse, so the viewer is unaffected.
        return Json.encodeToString(JsonObject.serializer(), obj)
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
    }
}
