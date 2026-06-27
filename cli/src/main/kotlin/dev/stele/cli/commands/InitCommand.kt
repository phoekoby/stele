package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.stele.cli.dbFile
import dev.stele.cli.steleDir
import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.db.schemaVersion
import dev.stele.core.store.GraphStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

class InitCommand : CliktCommand(
    name = "init",
    help = "Create the .stele graph (and wire Claude Code: drop the skill + register the MCP server)",
) {
    private val noSkill by option("--no-skill", help = "Do not write the Claude Code skill").flag()
    private val noMcp by option("--no-mcp", help = "Do not touch .mcp.json").flag()

    override fun run() {
        val dir = steleDir()
        if (!dir.exists()) dir.mkdirs()

        val path = dbFile().path
        val conn = openDb(path)
        migrate(conn)
        val counts = GraphStore(conn).counts()
        val version = schemaVersion(conn)
        conn.close()

        echo("✓ Stele initialized")
        echo("  db:     $path")
        echo("  schema: v$version")
        echo(
            "  graph:  ${counts.concepts} concepts · ${counts.artifacts} artifacts · " +
                "${counts.mentions} mentions · ${counts.edges} edges",
        )
        if (!noSkill) installSkill()
        if (!noMcp) registerMcp()
    }

    /** Drop the `stele-context` skill into the repo so Claude Code uses Stele automatically. */
    private fun installSkill() {
        val target = cwdFile(".claude/skills/stele-context/SKILL.md")
        if (target.exists()) {
            echo("  skill:  ${rel(target)} (already present)")
            return
        }
        val res = javaClass.getResourceAsStream("/skills/stele-context/SKILL.md") ?: return
        runCatching {
            target.parentFile?.mkdirs()
            res.use { target.outputStream().use { out -> it.copyTo(out) } }
            echo("  skill:  ${rel(target)} (added)")
        }
    }

    /**
     * Add/update the `stele` server in the project's `.mcp.json` so Claude Code auto-connects it with no
     * manual `claude mcp add`. Merges into any existing file (keeps other servers). The command points
     * at this install's own launcher + the graph's **absolute** path, so it works from any working dir.
     */
    private fun registerMcp() {
        val target = cwdFile(".mcp.json")
        val (cmd, prefix) = launcherCommand()
        val server = buildJsonObject {
            put("command", cmd)
            putJsonArray("args") { (prefix + listOf("mcp", "--db", dbFile().absolutePath)).forEach { add(it) } }
        }
        val pretty = Json { prettyPrint = true }
        val existing = if (target.exists()) {
            runCatching { pretty.parseToJsonElement(target.readText()).jsonObject }.getOrNull()
        } else {
            null
        }
        val existingServers = existing?.get("mcpServers") as? JsonObject
        val servers = buildJsonObject {
            existingServers?.forEach { (k, v) -> if (k != "stele") put(k, v) }
            put("stele", server)
        }
        val root = buildJsonObject {
            existing?.forEach { (k, v) -> if (k != "mcpServers") put(k, v) }
            put("mcpServers", servers)
        }
        runCatching {
            target.writeText(pretty.encodeToString(JsonObject.serializer(), root) + "\n")
            val others = existingServers?.keys?.count { it != "stele" } ?: 0
            echo("  mcp:    ${rel(target)} — `stele` server registered" + if (others > 0) " (kept $others other server(s))" else "")
        }
    }

    /** This install's launch command: installDist `bin/stele[.bat]`, else `java -jar` (fat jar), else `java -cp`. */
    private fun launcherCommand(): Pair<String, List<String>> {
        val win = System.getProperty("os.name").lowercase().contains("win")
        val self = runCatching { File(javaClass.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        if (self != null && self.isFile) {
            val launcher = self.parentFile?.parentFile?.let { File(it, "bin/" + if (win) "stele.bat" else "stele") }
            if (launcher != null && launcher.exists()) return launcher.absolutePath to emptyList()
            return javaExe(win) to listOf("-jar", self.absolutePath)
        }
        return javaExe(win) to listOf("-cp", System.getProperty("java.class.path"), "dev.stele.cli.MainKt")
    }

    private fun javaExe(win: Boolean) = File(System.getProperty("java.home"), "bin/" + if (win) "java.exe" else "java").path
    private fun cwdFile(path: String) = File(System.getProperty("user.dir"), path)
    private fun rel(f: File): String =
        runCatching { File(System.getProperty("user.dir")).toPath().relativize(f.toPath()).toString().replace('\\', '/') }
            .getOrDefault(f.path)
}
