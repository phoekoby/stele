package dev.stele.cli

import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore
import dev.stele.resolver.StaticLlmClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SteleHttpTest {
    private val client = HttpClient.newHttpClient()

    private fun get(port: Int, path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    // A concept name containing both a JSON-breaking quote and an HTML-breaking </script>.
    private val hostileName = "Bil\"ling</script>"

    private fun graphDb(dir: Path): String {
        val db = dir.resolve(".stele/graph.db")
        db.parent.toFile().mkdirs()
        val conn = openDb(db.toString())
        migrate(conn)
        GraphStore(conn).addConcept(hostileName, definition = "Plans and payments", boundedContext = "Billing")
        conn.close()
        return db.toString()
    }

    @Test
    fun `api search returns valid escaped JSON even for a hostile concept name`(@TempDir dir: Path) {
        val server = SteleHttp(graphDb(dir), null, null, StaticLlmClient("{}")).server("127.0.0.1", 0)
        server.start()
        try {
            val r = get(server.address.port, "/api/search?q=bil")
            assertEquals(200, r.statusCode())
            // Parses despite the embedded quote — proves the escaping is valid JSON…
            val arr = Json.parseToJsonElement(r.body()).jsonArray
            // …and the value round-trips to the original name exactly.
            assertEquals(hostileName, arr.first().jsonObject["name"]!!.jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `live viewer escapes a script breakout in the inlined data`(@TempDir dir: Path) {
        val server = SteleHttp(graphDb(dir), null, null, StaticLlmClient("{}")).server("127.0.0.1", 0)
        server.start()
        try {
            val body = get(server.address.port, "/").body()
            // The hostile </script> is neutralised to a unicode escape, so it can't close the tag.
            assertTrue(body.contains("\\u003c/script\\u003e"), "data-side </script> must be escaped")
            assertTrue(!body.contains("ling</script>"), "no raw breakout survives from the concept name")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `token gates data routes but not the static ask shell`(@TempDir dir: Path) {
        val server = SteleHttp(graphDb(dir), null, null, StaticLlmClient("{}"), token = "s3cret").server("127.0.0.1", 0)
        server.start()
        try {
            val port = server.address.port
            assertEquals(401, get(port, "/api/search?q=bil").statusCode(), "no token → denied")
            assertEquals(200, get(port, "/api/search?q=bil&token=s3cret").statusCode(), "token → allowed")
            assertEquals(401, get(port, "/").statusCode(), "the viewer inlines data → gated")
            assertEquals(200, get(port, "/ask").statusCode(), "the ask shell has no data → open")
        } finally {
            server.stop(0)
        }
    }
}
