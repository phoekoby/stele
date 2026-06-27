package dev.stele.mcp

import dev.stele.core.store.GraphStore
import dev.stele.core.store.UsageLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.Writer

/**
 * Minimal Model Context Protocol server over stdio (newline-delimited JSON-RPC
 * 2.0) — the serving layer. Hand-rolled to keep the tool zero-infra/local; it
 * exposes the concept spine to agents via `concept_context` and `why_code`.
 */
class McpServer(private val store: GraphStore, private val usage: UsageLog? = null) {
    private val json = Json { }

    fun serve(input: BufferedReader, output: Writer) {
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val msg = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            val id = msg["id"]
            val method = msg["method"].asString() ?: continue
            if (method.startsWith("notifications/")) continue // notifications get no reply

            val result: JsonObject? = try {
                when (method) {
                    "initialize" -> initialize()
                    "tools/list" -> toolsList()
                    "tools/call" -> toolsCall(msg["params"] as? JsonObject)
                    "ping" -> buildJsonObject {}
                    else -> null
                }
            } catch (e: Exception) {
                // Never let one bad request kill the server — report it and keep serving.
                if (id != null) error(output, id, -32603, "Internal error: ${e.message}")
                continue
            }
            if (id == null) continue
            if (result != null) reply(output, id, result) else error(output, id, -32601, "Method not found: $method")
        }
    }

    private fun initialize() = buildJsonObject {
        put("protocolVersion", "2024-11-05")
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
            put("name", "stele")
            put("version", "0.1.0")
        }
    }

    private fun toolsList() = buildJsonObject {
        putJsonArray("tools") {
            addJsonObject {
                put("name", "concept_context")
                put(
                    "description",
                    "Resolve a domain concept (by name or alias) to its definition, bounded context, " +
                        "and the code that implements it across languages and services.",
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("concept") { put("type", "string"); put("description", "Concept name or alias, e.g. Auth") }
                    }
                    putJsonArray("required") { add("concept") }
                }
            }
            addJsonObject {
                put("name", "why_code")
                put(
                    "description",
                    "Given a repo-relative file or directory path, list the domain concept(s) that code " +
                        "implements, with their definitions — i.e. what product capability this code is part of.",
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string"); put("description", "Repo-relative file or directory path") }
                    }
                    putJsonArray("required") { add("path") }
                }
            }
            addJsonObject {
                put("name", "context_for_code")
                put(
                    "description",
                    "Full product context for the file you're editing: the concept(s) this code is part of, " +
                        "their product rules, related concepts, and docs. Call before changing code so you respect product rules.",
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string"); put("description", "Repo-relative path of the file being edited") }
                    }
                    putJsonArray("required") { add("path") }
                }
            }
        }
    }

    private fun toolsCall(params: JsonObject?): JsonObject {
        val name = params?.get("name").asString()
        val args = params?.get("arguments") as? JsonObject
        val query = (if (name == "concept_context") args?.get("concept") else args?.get("path")).asString().orEmpty()
        val start = System.currentTimeMillis()
        val text = when (name) {
            "concept_context" -> conceptContext(query)
            "why_code" -> whyCode(query)
            "context_for_code" -> {
                val path = query.replace('\\', '/')
                if (path.isBlank()) {
                    "Provide a path."
                } else {
                    val (callsOut, callsIn) = store.callsForPath(path)
                    formatCodeContext(path, store.codeContext(path), callsOut, callsIn)
                }
            }
            else -> "Unknown tool: $name"
        }
        usage?.record(name ?: "?", query, isHit(text), text.length, System.currentTimeMillis() - start)
        return buildJsonObject {
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
        }
    }

    /** A call "hit" when it resolved to real context, not a placeholder/miss. */
    private fun isHit(text: String): Boolean = !(
        text.startsWith("No concept") || text.startsWith("No concepts") ||
            text.startsWith("Provide") || text.startsWith("Unknown tool") ||
            text.contains("no concept linked")
        )

    private fun conceptContext(term: String): String {
        if (term.isBlank()) return "Provide a concept name."
        val c = store.resolveConcept(term) ?: return "No concept matching \"$term\". Try `stele build-ontology` first."
        val impls = store.implementersOf(c.id)
        val docs = store.describingDocs(c.id)
        val related = store.relatedConcepts(c.id)
        val rules = store.rulesFor(c.id)
        return buildString {
            append("concept: ${c.name}")
            c.boundedContext?.let { append("  [$it]") }
            append('\n')
            c.definition?.let { append(it).append('\n') }
            if (c.aliases.isNotEmpty()) append("aliases: ${c.aliases.joinToString(", ")}\n")
            if (related.isNotEmpty()) append("related concepts: ${related.take(10).joinToString(", ") { it.name }}\n")
            if (rules.isNotEmpty()) {
                append("\nproduct rules:\n")
                for (r in rules.take(8)) append("  ‣ ${r.title}\n")
            }
            if (docs.isNotEmpty()) {
                append("\ndescribed in product docs:\n")
                for (d in docs.take(6)) {
                    append("  • ${d.title}  (${d.ref})\n")
                    snippet(d.body)?.let { append("      $it\n") }
                }
            }
            val byFile = impls.groupBy { it.ref.substringBefore('#') }
            append("\nimplemented by ${impls.size} symbols across ${byFile.size} files:\n")
            for ((file, syms) in byFile.entries.sortedBy { it.key }) {
                append("  ${lang(file)} $file\n")
                append("      ${syms.joinToString(", ") { it.title ?: it.ref }}\n")
            }
        }.trimEnd()
    }

    private fun whyCode(path: String): String {
        if (path.isBlank()) return "Provide a path."
        val hits = store.conceptsForPath(path.replace('\\', '/'))
        if (hits.isEmpty()) return "No concepts found for \"$path\" — run `stele ingest symbols` and `stele build-ontology`."
        return buildString {
            append("$path is part of:\n")
            for ((c, syms) in hits) {
                append("  • ${c.name}")
                c.boundedContext?.let { append("  [$it]") }
                c.definition?.let { append(" — $it") }
                append("\n      symbols: ${syms.joinToString(", ")}\n")
            }
        }.trimEnd()
    }

    private fun reply(out: Writer, id: JsonElement, result: JsonObject) = send(
        out,
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        },
    )

    private fun error(out: Writer, id: JsonElement, code: Int, message: String) = send(
        out,
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") { put("code", code); put("message", message) }
        },
    )

    private fun send(out: Writer, obj: JsonObject) {
        out.write(json.encodeToString(JsonObject.serializer(), obj))
        out.write("\n")
        out.flush()
    }
}

private fun snippet(body: String?): String? =
    body?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(180)

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun lang(file: String): String = when {
    file.endsWith(".go") -> "[go]"
    file.endsWith(".ts") || file.endsWith(".tsx") -> "[ts]"
    file.endsWith(".kt") || file.endsWith(".kts") -> "[kt]"
    file.endsWith(".py") -> "[py]"
    else -> "[..]"
}
