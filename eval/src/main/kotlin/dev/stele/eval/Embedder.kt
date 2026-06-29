package dev.stele.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.sqrt

/** Turns text into a unit vector for the [VectorRagArm] baseline. */
interface Embedder {
    val name: String
    fun embed(text: String): FloatArray
}

/** Cosine similarity of two equal-length vectors (both expected L2-normalized → just the dot). */
fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) dot += a[i] * b[i]
    return dot
}

private fun l2normalize(v: FloatArray): FloatArray {
    var sum = 0f
    for (x in v) sum += x * x
    val norm = sqrt(sum)
    if (norm == 0f) return v
    for (i in v.indices) v[i] /= norm
    return v
}

/**
 * Offline default — the hashing trick: tokenize, hash each token into a fixed-width
 * bag-of-words vector, L2-normalize. No model, no network; deterministic and free.
 * It captures lexical overlap (not semantics), so it's a fair *floor* for vector RAG —
 * a real embedding model (see [OllamaEmbedder]) only does better, never worse.
 */
class HashingEmbedder(private val dim: Int = 512) : Embedder {
    override val name = "hashing:$dim"

    override fun embed(text: String): FloatArray {
        val v = FloatArray(dim)
        for (m in TOKEN.findAll(text.lowercase())) {
            val t = m.value
            if (t.length < 3) continue
            val h = (t.hashCode() % dim + dim) % dim
            v[h] += 1f
        }
        return l2normalize(v)
    }

    companion object {
        private val TOKEN = Regex("[a-z][a-z0-9]+")
    }
}

/**
 * Real embeddings via Ollama (`/api/embeddings`) — e.g. `nomic-embed-text`. Same
 * local-first stance as the resolver's [dev.stele.resolver.OllamaClient]: OSS, offline,
 * no API key. Vectors are L2-normalized so [cosine] is a plain dot product.
 */
class OllamaEmbedder(
    private val model: String = "nomic-embed-text",
    private val baseUrl: String = "http://localhost:11434",
    private val timeout: Duration = Duration.ofMinutes(5),
) : Embedder {
    override val name = "ollama:$model"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun embed(text: String): FloatArray {
        val body = json.encodeToString(Request(model = model, prompt = text))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/embeddings"))
            .timeout(timeout)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val res = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { throw RuntimeException("Ollama unreachable at $baseUrl — is `ollama serve` running? (${it.message})") }
        if (res.statusCode() !in 200..299) {
            throw RuntimeException("Ollama ${res.statusCode()}: ${res.body().take(400)}")
        }
        val emb = json.decodeFromString<Response>(res.body()).embedding
        return l2normalize(FloatArray(emb.size) { emb[it] })
    }

    @Serializable
    private data class Request(val model: String, val prompt: String)

    @Serializable
    private data class Response(val embedding: List<Float>)
}
