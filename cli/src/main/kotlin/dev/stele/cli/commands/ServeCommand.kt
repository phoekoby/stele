package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.stele.cli.AskService
import dev.stele.cli.EmbedderFactory
import dev.stele.cli.GraphHtml
import dev.stele.cli.LlmFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.core.store.UsageLog
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Remote serving for the humans: one local HTTP server with the LIVE graph viewer
 * (`/`), a support ask page (`/ask`), and a small JSON API. Zero dependencies —
 * the JDK's built-in HttpServer. Binds localhost by default; `--host 0.0.0.0`
 * exposes it to the network (NO auth — trusted networks only).
 */
class ServeCommand : CliktCommand(
    name = "serve",
    help = "Serve the graph over HTTP: live viewer (/), ask page (/ask), JSON API (/api/*)",
) {
    private val port by option("--port").int().default(4600)
    private val host by option("--host", help = "Bind address (0.0.0.0 to expose — no auth!)").default("127.0.0.1")
    private val resolvedOnly by option("--resolved-only", help = "Viewer: only canonicalized concepts").flag()

    override fun run() {
        val dbFile = requireDb()
        val conn = openDb(dbFile.path)
        val store = GraphStore(conn)
        val cfg = ConfigLoader.findAndLoad()?.llm
        val repoRoot = dbFile.parentFile?.parentFile
        val usage = UsageLog(File(dbFile.parentFile, "usage.jsonl"))
        val llm = LlmFactory.build(
            cfg?.provider ?: "ollama", cfg?.model, cfg?.ollamaUrl ?: "http://localhost:11434",
            null, cfg?.baseUrl, cfg?.apiKeyEnv,
        )

        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        // One worker: the sqlite connection isn't thread-safe, and a team-sized load
        // doesn't need more. An LLM answer blocks the queue for a few seconds — fine.
        server.executor = Executors.newSingleThreadExecutor()

        server.createContext("/") { ex ->
            handle(ex) {
                if (ex.requestURI.path != "/") return@handle notFound(ex)
                val (html, _) = GraphHtml.render(store, resolvedOnly)
                respond(ex, 200, "text/html; charset=utf-8", html)
            }
        }
        server.createContext("/ask") { ex ->
            handle(ex) {
                val page = javaClass.getResourceAsStream("/serve/ask.html")!!.bufferedReader().readText()
                respond(ex, 200, "text/html; charset=utf-8", page)
            }
        }
        server.createContext("/api/ask") { ex ->
            handle(ex) {
                val q = param(ex, "q") ?: return@handle respond(ex, 400, JSON, """{"error":"missing ?q="}""")
                val started = System.currentTimeMillis()
                val res = AskService.ask(store, cfg, repoRoot, q, llm = llm)
                usage.record("http:ask", q, hit = res != null, chars = res?.context?.length ?: 0, ms = System.currentTimeMillis() - started)
                if (res == null) return@handle respond(ex, 200, JSON, """{"concepts":[],"error":"couldn't resolve the question to a concept"}""")
                respond(
                    ex, 200, JSON,
                    """{"concepts":[${res.concepts.joinToString(",") { "\"${esc(it.name)}\"" }}],""" +
                        """"answer":"${esc(res.answer ?: "")}"}""",
                )
            }
        }
        server.createContext("/api/search") { ex ->
            handle(ex) {
                val q = param(ex, "q") ?: return@handle respond(ex, 400, JSON, """{"error":"missing ?q="}""")
                var hits = store.searchConcepts(q, 10)
                if (hits.isEmpty()) {
                    val embedder = EmbedderFactory.fromConfig(cfg)
                    runCatching { embedder.embedQuery(q) }.getOrNull()?.let {
                        hits = store.resolveSemanticConcepts(it, embedder.name, topK = 5)
                    }
                }
                respond(
                    ex, 200, JSON,
                    hits.joinToString(",", "[", "]") {
                        """{"name":"${esc(it.name)}","context":"${esc(it.boundedContext ?: "")}","definition":"${esc(it.definition ?: "")}"}"""
                    },
                )
            }
        }
        server.createContext("/api/concept") { ex ->
            handle(ex) {
                val name = param(ex, "name") ?: return@handle respond(ex, 400, JSON, """{"error":"missing ?name="}""")
                val res = AskService.ask(store, cfg, repoRoot, name, top = 1, llm = null)
                    ?: return@handle respond(ex, 404, JSON, """{"error":"no such concept"}""")
                respond(ex, 200, JSON, """{"name":"${esc(res.concepts.first().name)}","context":"${esc(res.context)}"}""")
            }
        }

        echo("stele serve — graph: http://$host:$port/   ask: http://$host:$port/ask   [answering via ${llm.name}]")
        if (host != "127.0.0.1" && host != "localhost") {
            echo("⚠ bound to $host with NO auth — expose only on trusted networks")
        }
        server.start()
        Thread.currentThread().join() // serve until killed
    }

    private fun handle(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            runCatching { respond(ex, 500, JSON, """{"error":"${esc(e.message ?: "internal error")}"}""") }
        } finally {
            ex.close()
        }
    }

    private fun respond(ex: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun notFound(ex: HttpExchange) = respond(ex, 404, JSON, """{"error":"not found"}""")

    private fun param(ex: HttpExchange, key: String): String? =
        ex.requestURI.rawQuery?.split('&')?.firstNotNullOfOrNull {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") }
            if (k == key && v.isNotBlank()) URLDecoder.decode(v, Charsets.UTF_8) else null
        }

    private fun esc(s: String): String = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\"); '"' -> append("\\\"")
            '\n' -> append("\\n"); '\r' -> {}; '\t' -> append("\\t")
            else -> if (ch < ' ') append(' ') else append(ch)
        }
    }

    companion object {
        private const val JSON = "application/json; charset=utf-8"
    }
}
