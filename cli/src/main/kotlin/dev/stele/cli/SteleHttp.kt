package dev.stele.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.stele.cli.config.LlmConfig
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.core.store.UsageLog
import dev.stele.resolver.LlmClient
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * The HTTP surface behind `stele serve`, extracted so it can be started on an ephemeral
 * port in tests. Thread-safe by construction: a fixed worker pool, and each request opens
 * its OWN short-lived SQLite connection (WAL allows concurrent readers) — so one slow LLM
 * answer no longer blocks every other request, and no Connection is shared across threads.
 *
 * When [token] is non-blank, every data/LLM route requires it (via `?token=` or
 * `Authorization: Bearer`); the static `/ask` shell stays open and forwards the token itself.
 */
class SteleHttp(
    private val dbPath: String,
    private val cfg: LlmConfig?,
    private val repoRoot: File?,
    private val llm: LlmClient,
    private val usage: UsageLog? = null,
    private val token: String = "",
    private val resolvedOnly: Boolean = false,
    private val workers: Int = 4,
) {
    private val embedder = EmbedderFactory.fromConfig(cfg)

    fun server(host: String, port: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.executor = Executors.newFixedThreadPool(workers.coerceIn(1, 16))

        // The live graph viewer inlines the whole graph → treat as data (token-gated).
        server.createContext("/") { ex ->
            gated(ex) { store ->
                if (ex.requestURI.path != "/") respond(ex, 404, JSON, ERR_NOT_FOUND)
                else respond(ex, 200, HTML, GraphHtml.render(store, resolvedOnly).first)
            }
        }
        // Static shell, no graph data — served without a token; it forwards ?token= to the API.
        server.createContext("/ask") { ex ->
            handle(ex) {
                respond(ex, 200, HTML, javaClass.getResourceAsStream("/serve/ask.html")!!.bufferedReader().readText())
            }
        }
        server.createContext("/api/ask") { ex ->
            gated(ex) { store ->
                val q = param(ex, "q") ?: return@gated respond(ex, 400, JSON, errBody("missing ?q="))
                val started = clock()
                val res = AskService.ask(store, cfg, repoRoot, q, llm = llm)
                usage?.let { synchronized(it) { it.record("http:ask", q, hit = res != null, chars = res?.context?.length ?: 0, ms = clock() - started) } }
                if (res == null) respond(ex, 200, JSON, """{"concepts":[],"error":"couldn't resolve the question to a concept"}""")
                else respond(
                    ex, 200, JSON,
                    """{"concepts":[${res.concepts.joinToString(",") { jsonStr(it.name) }}],"answer":${jsonStr(res.answer ?: "")}}""",
                )
            }
        }
        server.createContext("/api/search") { ex ->
            gated(ex) { store ->
                val q = param(ex, "q") ?: return@gated respond(ex, 400, JSON, errBody("missing ?q="))
                var hits = store.searchConcepts(q, 10)
                if (hits.isEmpty()) {
                    runCatching { embedder.embedQuery(q) }.getOrNull()?.let {
                        hits = store.resolveSemanticConcepts(it, embedder.name, topK = 5)
                    }
                }
                respond(
                    ex, 200, JSON,
                    hits.joinToString(",", "[", "]") {
                        """{"name":${jsonStr(it.name)},"context":${jsonStr(it.boundedContext ?: "")},"definition":${jsonStr(it.definition ?: "")}}"""
                    },
                )
            }
        }
        server.createContext("/api/concept") { ex ->
            gated(ex) { store ->
                val name = param(ex, "name") ?: return@gated respond(ex, 400, JSON, errBody("missing ?name="))
                val res = AskService.ask(store, cfg, repoRoot, name, top = 1, llm = null)
                if (res == null) respond(ex, 404, JSON, errBody("no such concept"))
                else respond(ex, 200, JSON, """{"name":${jsonStr(res.concepts.first().name)},"context":${jsonStr(res.context)}}""")
            }
        }
        return server
    }

    /** Auth-check, then open a per-request connection and hand a store to [block]. */
    private fun gated(ex: HttpExchange, block: (GraphStore) -> Unit) = handle(ex) {
        if (!authorized(ex)) return@handle respond(ex, 401, JSON, errBody("unauthorized — add ?token= or Authorization: Bearer"))
        val conn = openDb(dbPath)
        try {
            block(GraphStore(conn))
        } finally {
            conn.close()
        }
    }

    private fun authorized(ex: HttpExchange): Boolean {
        if (token.isBlank()) return true
        val header = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
        return header == token || param(ex, "token") == token
    }

    private fun handle(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            runCatching { respond(ex, 500, JSON, errBody(e.message ?: "internal error")) }
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

    private fun param(ex: HttpExchange, key: String): String? =
        ex.requestURI.rawQuery?.split('&')?.firstNotNullOfOrNull {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") }
            if (k == key && v.isNotBlank()) URLDecoder.decode(v, Charsets.UTF_8) else null
        }

    private fun errBody(msg: String) = """{"error":${jsonStr(msg)}}"""

    private fun clock() = System.currentTimeMillis()

    /** A JSON string literal (quoted + escaped), so API values can never break the JSON. */
    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '\\' -> append("\\\\"); '"' -> append("\\\"")
            '\n' -> append("\\n"); '\r' -> {}; '\t' -> append("\\t")
            else -> if (ch < ' ') append(' ') else append(ch)
        }
        append('"')
    }

    companion object {
        private const val JSON = "application/json; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"
        private const val ERR_NOT_FOUND = """{"error":"not found"}"""
    }
}
