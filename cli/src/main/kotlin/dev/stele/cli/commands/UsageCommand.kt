package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import dev.stele.cli.requireDb
import dev.stele.core.store.UsageLog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UsageCommand : CliktCommand(
    name = "usage",
    help = "How often agents pulled Stele context via MCP, and how much context it served",
) {
    override fun run() {
        val events = UsageLog(File(requireDb().parentFile, "usage.jsonl")).events()
        if (events.isEmpty()) {
            echo("No usage recorded yet. Register `stele mcp` in your agent and use concept_context / context_for_code / why_code.")
            return
        }

        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
        val first = day.format(Instant.ofEpochMilli(events.minOf { it.ts }))
        val last = day.format(Instant.ofEpochMilli(events.maxOf { it.ts }))
        val hits = events.count { it.hit }
        val chars = events.sumOf { it.chars.toLong() }
        val byTool = events.groupingBy { it.tool }.eachCount().entries.sortedByDescending { it.value }
        val topQ = events.filter { it.query.isNotBlank() }
            .groupingBy { it.query }.eachCount().entries.sortedByDescending { it.value }.take(8)

        echo("Stele MCP usage — ${events.size} calls, $first..$last")
        echo("  by tool:          ${byTool.joinToString(", ") { "${it.key} ${it.value}" }}")
        echo("  resolve hit-rate: ${pct(hits, events.size)}% ($hits/${events.size})")
        echo("  context served:   ${kb(chars)} (~${chars / 4 / 1000}k tokens of curated context pulled, not discovered)")
        echo("  avg latency:      ${events.map { it.ms }.average().toInt()} ms")
        if (topQ.isNotEmpty()) echo("  top queries:      ${topQ.joinToString(", ") { "${it.key} ${it.value}" }}")
    }

    private fun pct(n: Int, d: Int) = if (d == 0) 0 else 100 * n / d
    private fun kb(bytes: Long) = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"
}
