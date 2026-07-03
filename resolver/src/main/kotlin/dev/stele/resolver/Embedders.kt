package dev.stele.resolver

import dev.stele.core.embed.Embedder
import dev.stele.core.embed.l2normalize
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Offline default — the hashing trick: tokenize, hash each token into a fixed-width
 * bag-of-words vector, L2-normalize. No model, no network; deterministic and free.
 * Captures lexical overlap only — the floor a real embedding model improves on.
 */
class HashingEmbedder(private val dim: Int = 4096) : Embedder {
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
 * local-first stance as [OllamaClient]: OSS, offline, no API key.
 */
class OllamaEmbedder(
    private val model: String = "nomic-embed-text",
    private val baseUrl: String = "http://localhost:11434",
    private val timeout: Duration = Duration.ofMinutes(5),
) : Embedder {
    override val name = "ollama:$model"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    // nomic is an asymmetric model: it was TRAINED with task prefixes, and skipping them
    // measurably degrades retrieval quality.
    private val isNomic = model.contains("nomic")

    override fun embed(text: String): FloatArray =
        request(if (isNomic) "search_document: $text" else text)

    override fun embedQuery(text: String): FloatArray =
        request(if (isNomic) "search_query: $text" else text)

    private fun request(text: String): FloatArray {
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
