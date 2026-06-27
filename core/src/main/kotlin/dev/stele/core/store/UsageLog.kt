package dev.stele.core.store

import java.io.File

/** One served MCP/context call: which tool, what was asked, did it resolve, how much context, how fast. */
data class UsageEvent(
    val ts: Long,
    val tool: String,
    val query: String,
    val hit: Boolean,
    val chars: Int,
    val ms: Long,
)

/**
 * Append-only usage telemetry next to the graph (`.stele/usage.jsonl`). The MCP
 * server records every tool call so `stele usage` can report how often the agent
 * pulls Stele context, the resolve hit-rate, and how much curated context it
 * served (a proxy for the discovery work it saved). Local-only, never networked.
 */
class UsageLog(private val file: File) {

    fun record(tool: String, query: String, hit: Boolean, chars: Int, ms: Long) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(
                """{"ts":${System.currentTimeMillis()},"tool":"${esc(tool)}",""" +
                    """"q":"${esc(query)}","hit":$hit,"chars":$chars,"ms":$ms}""" + "\n",
            )
        }
    }

    fun events(): List<UsageEvent> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull(::parse)

    private fun parse(line: String): UsageEvent? = runCatching {
        fun str(k: String) = Regex(""""$k":"((?:[^"\\]|\\.)*)"""").find(line)?.groupValues?.get(1)
        fun num(k: String) = Regex(""""$k":(-?\d+)""").find(line)?.groupValues?.get(1)?.toLong()
        fun bool(k: String) = Regex(""""$k":(true|false)""").find(line)?.groupValues?.get(1) == "true"
        UsageEvent(num("ts")!!, str("tool")!!, unesc(str("q") ?: ""), bool("hit"), num("chars")?.toInt() ?: 0, num("ms") ?: 0)
    }.getOrNull()

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    private fun unesc(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
}
