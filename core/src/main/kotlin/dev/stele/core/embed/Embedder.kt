package dev.stele.core.embed

import kotlin.math.sqrt

/**
 * Turns text into a unit vector. The interface lives in :core (dependency-free) so the
 * graph store and MCP server can rank by cosine; implementations (hashing, Ollama HTTP)
 * live in :resolver. Vectors are L2-normalized, so cosine is a plain dot product.
 */
interface Embedder {
    val name: String

    /** Embed a document/section being indexed. */
    fun embed(text: String): FloatArray

    /** Embed a query. Asymmetric models (e.g. nomic) need distinct task prefixes. */
    fun embedQuery(text: String): FloatArray = embed(text)
}

/** Cosine of two vectors (dot product — both are expected L2-normalized). */
fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) dot += a[i] * b[i]
    return dot
}

fun l2normalize(v: FloatArray): FloatArray {
    var sum = 0f
    for (x in v) sum += x * x
    val norm = sqrt(sum)
    if (norm == 0f) return v
    for (i in v.indices) v[i] /= norm
    return v
}
