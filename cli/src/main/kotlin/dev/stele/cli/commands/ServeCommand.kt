package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.stele.cli.EmbedderFactory
import dev.stele.cli.LlmFactory
import dev.stele.cli.SteleHttp
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.requireDb
import dev.stele.core.store.UsageLog
import java.io.File

/**
 * Remote serving for the humans: one local HTTP server with the LIVE graph viewer
 * (`/`), a support ask page (`/ask`), and a small JSON API. Zero dependencies —
 * the JDK's built-in HttpServer. Binds localhost by default; a non-loopback bind
 * requires a `--token` (or `--insecure`) so the graph + LLM aren't left open.
 */
class ServeCommand : CliktCommand(
    name = "serve",
    help = "Serve the graph over HTTP: live viewer (/), ask page (/ask), JSON API (/api/*)",
) {
    private val port by option("--port").int().default(4600)
    private val host by option("--host", help = "Bind address (0.0.0.0 to expose — needs --token)").default("127.0.0.1")
    private val resolvedOnly by option("--resolved-only", help = "Viewer: only canonicalized concepts").flag()
    private val tokenOpt by option("--token", help = "Require this token on data routes (?token= or Authorization: Bearer). Env: STELE_SERVE_TOKEN")
    private val insecure by option("--insecure", help = "Allow a non-loopback bind with no token").flag()

    override fun run() {
        val dbFile = requireDb()
        val cfg = ConfigLoader.findAndLoad()?.llm
        val token = tokenOpt ?: System.getenv("STELE_SERVE_TOKEN") ?: ""

        val loopback = host in setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
        if (!loopback && token.isBlank() && !insecure) {
            throw PrintMessage(
                "Refusing to bind $host with no token — the graph and the LLM would be open to the network.\n" +
                    "  • set --token <secret> (or STELE_SERVE_TOKEN), then open /?token=<secret>\n" +
                    "  • or pass --insecure to override on a trusted network",
                statusCode = 1,
                printError = true,
            )
        }

        val llm = LlmFactory.build(
            cfg?.provider ?: "ollama", cfg?.model, cfg?.ollamaUrl ?: "http://localhost:11434",
            null, cfg?.baseUrl, cfg?.apiKeyEnv,
        )
        val http = SteleHttp(
            dbPath = dbFile.path,
            cfg = cfg,
            repoRoot = dbFile.parentFile?.parentFile,
            llm = llm,
            usage = UsageLog(File(dbFile.parentFile, "usage.jsonl")),
            token = token,
            resolvedOnly = resolvedOnly,
        )
        val server = http.server(host, port)
        val suffix = if (token.isNotBlank()) "?token=<token>" else ""
        echo("stele serve — graph: http://$host:$port/$suffix   ask: http://$host:$port/ask$suffix   [answering via ${llm.name}]")
        EmbedderFactory.floorWarning(EmbedderFactory.fromConfig(cfg))?.let { echo(it) }
        if (token.isNotBlank()) echo("  token required on / and /api/*")
        if (!loopback && token.isBlank()) echo("⚠ bound to $host with NO auth (--insecure) — trusted networks only")
        server.start()
        Thread.currentThread().join() // serve until killed
    }
}
