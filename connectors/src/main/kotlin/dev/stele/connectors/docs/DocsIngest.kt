package dev.stele.connectors.docs

import dev.stele.core.model.ArtifactKind
import dev.stele.core.model.Concept
import dev.stele.core.model.EdgeSource
import dev.stele.core.model.EdgeStatus
import dev.stele.core.model.EdgeType
import dev.stele.core.model.Layer
import dev.stele.core.store.GraphStore
import java.io.File

data class DocsIngestResult(
    val docs: Int,
    val sections: Int,
    val links: Int,
    val aliasesAdded: Int,
    val relations: Int,
    val rules: Int,
)

// Sentences expressing a product constraint become `rule` artifacts.
private val CONSTRAINT = Regex(
    "\\b(must not|must|cannot|can only|may not|shall|should not|should|require[sd]?|never|always|" +
        "forbidden|not allowed|mandatory|at most|at least|no more than|only|within \\d+)\\b" +
        "|(должн|обязан|обязательн|только|нельзя|запрещ|не может|не дол|в течение \\d+|не более|не менее|всегда|никогда)",
    RegexOption.IGNORE_CASE,
)
private val SENTENCE = Regex("(?<=[.!?])\\s+|\\n+")
private val WS = Regex("\\s+")

private const val INTRO = "(intro)"
private val HEADING = Regex("^#{1,6}\\s+(.*)$")
private val CAMEL = Regex("([a-z0-9])([A-Z])")
private val NON_SLUG = Regex("[^a-z0-9]+")
private val LEAD_NUM = Regex("^\\s*\\d+[.)]\\s*")
private val DOC_EXT = setOf("md", "markdown", "mdx")
private val IGNORE = setOf(
    "node_modules", ".git", ".stele", "dist", "build", "target",
    "vendor", ".next", "out", ".gradle", ".claude",
)

// Section titles that aren't product terms — never promoted to aliases.
private val GENERIC_HEADINGS = setOf(
    "overview", "introduction", "intro", "getting started", "usage", "examples", "example",
    "api", "reference", "configuration", "config", "installation", "install", "faq", "changelog",
    "contents", "summary", "notes", "links", "see also", "prerequisites", "setup", "quick start",
    "architecture", "data model", "описание", "обзор", "примеры", "использование", "настройка",
)

private class Term(val regex: Regex, val conceptId: String)

/**
 * The product side of the bridge: ingest Markdown docs, split into sections, and
 * link each section to the concept(s) it mentions by name/alias (`describes`
 * edge, deterministic). Only sections that touch a concept are kept, so the
 * graph stays the product↔concept surface, not every paragraph.
 *
 * This grounds concepts in the team's *product language*: after it,
 * `concept_context` returns the product prose alongside the code.
 */
/** One source document feeding the linker: a stable ref, a display name, and markdown-ish content (# headings). */
private data class RawDoc(val ref: String, val name: String, val content: String)

fun ingestDocs(store: GraphStore, rootArg: String): DocsIngestResult {
    val root = File(rootArg).absoluteFile.normalize()
    val rootPath = root.toPath()
    val raws = walkDocs(root).mapNotNull { file ->
        val content = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
        RawDoc(rootPath.relativize(file.toPath()).toString().replace('\\', '/'), file.name, content)
    }.toList()
    return linkDocs(store, raws, "docs")
}

/**
 * The *other* connectors path: ingest external pages (any URL) as product docs — fetch each, strip HTML
 * to text (keeping headings), and link to concepts exactly like local Markdown. This is how Jira /
 * Confluence / any docs site feeds the concept spine alongside code.
 */
fun ingestWeb(store: GraphStore, urls: List<String>): DocsIngestResult {
    val raws = urls.mapNotNull { url -> fetchUrl(url)?.let { RawDoc(url, pageName(url), htmlToText(it)) } }
    return linkDocs(store, raws, "web")
}

private fun linkDocs(store: GraphStore, raws: List<RawDoc>, source: String): DocsIngestResult {
    val vocab = buildVocabulary(store.conceptVocabulary())
    if (vocab.isEmpty()) return DocsIngestResult(0, 0, 0, 0, 0, 0)

    val docs = HashSet<String>()
    val aliasCandidates = HashMap<String, LinkedHashSet<String>>() // conceptId -> product headings
    val cooccurrence = HashMap<Pair<String, String>, Int>() // concept pair -> sections describing both
    val ruleIds = HashSet<String>()
    var sections = 0
    var links = 0

    for (raw in raws) {
        for ((idx, section) in splitSections(raw.content).withIndex()) {
            val (heading, body) = section
            val hits = LinkedHashMap<String, Boolean>() // conceptId -> matched in heading (strong)
            for (term in vocab) {
                when {
                    term.regex.containsMatchIn(heading) -> hits[term.conceptId] = true
                    term.regex.containsMatchIn(body) -> hits.putIfAbsent(term.conceptId, false)
                }
            }
            if (hits.isEmpty()) continue

            val title = if (heading == INTRO) raw.name else heading
            val anchor = if (heading == INTRO) "intro-$idx" else slug(heading)
            val docId = store.addArtifact(
                kind = ArtifactKind.DOC,
                layer = Layer.PRODUCT,
                source = source,
                ref = "${raw.ref}#$anchor",
                title = title,
                body = body.take(2000),
            )
            docs.add(raw.ref)
            sections++

            for ((conceptId, strong) in hits) {
                store.addEdge(
                    srcId = docId,
                    dstId = conceptId,
                    type = EdgeType.DESCRIBES,
                    source = EdgeSource.DETERMINISTIC,
                    confidence = if (strong) 0.9 else 0.6,
                    evidence = listOf("$source:${raw.ref}"),
                    status = EdgeStatus.PROPOSED,
                )
                links++
                // A heading that literally contains the concept term is a product
                // phrasing of it (e.g. "Live session" -> Authentication) → alias.
                if (strong) aliasFromHeading(heading)?.let {
                    aliasCandidates.getOrPut(conceptId) { LinkedHashSet() }.add(it)
                }
            }

            // concept<->concept: co-description in a section is a relationship signal.
            val ids = hits.keys.toList()
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val key = if (ids[i] < ids[j]) ids[i] to ids[j] else ids[j] to ids[i]
                cooccurrence[key] = (cooccurrence[key] ?: 0) + 1
            }

            // rules: only from sections a concept owns by HEADING (strong match) —
            // a weak body mention is too noisy to attach product constraints to.
            val owners = hits.filterValues { it }.keys
            if (owners.isNotEmpty()) for (sentence in rulesIn(body)) {
                val ruleId = store.addArtifact(
                    kind = ArtifactKind.RULE,
                    layer = Layer.PRODUCT,
                    source = source,
                    ref = "rule:" + Integer.toHexString(sentence.hashCode()),
                    title = sentence.take(160),
                    body = sentence,
                )
                ruleIds.add(ruleId)
                for (conceptId in owners) {
                    store.addEdge(
                        srcId = ruleId,
                        dstId = conceptId,
                        type = EdgeType.CONSTRAINS,
                        source = EdgeSource.INFERRED,
                        confidence = 0.6,
                        evidence = listOf("$source:${raw.ref}"),
                        status = EdgeStatus.PROPOSED,
                    )
                }
            }
        }
    }

    var aliasesAdded = 0
    for ((conceptId, headings) in aliasCandidates) aliasesAdded += store.addAliases(conceptId, headings)

    var relations = 0
    for ((pair, count) in cooccurrence) {
        if (count < 2) continue // need >= 2 co-describing sections to call it a relation
        store.addEdge(
            srcId = pair.first,
            dstId = pair.second,
            type = EdgeType.RELATES,
            source = EdgeSource.INFERRED,
            confidence = minOf(0.9, 0.3 + count * 0.1),
            evidence = listOf("co-described in $count doc sections"),
            status = EdgeStatus.PROPOSED,
        )
        relations++
    }

    return DocsIngestResult(docs.size, sections, links, aliasesAdded, relations, ruleIds.size)
}

// Markdown tables, URLs, connection strings and schema column defs trip the
// constraint keywords ("only", "required", "нельзя") but aren't product rules.
private val SCHEMA_TOKEN = Regex("\\b(FK|PK|uuid|varchar)\\b", RegexOption.IGNORE_CASE)

internal fun isProseRule(s: String): Boolean =
    s.length in 12..240 &&
        CONSTRAINT.containsMatchIn(s) &&
        !s.contains('|') && // markdown table row
        !s.contains("://") && // URL / connection string
        s.count { it == '`' } < 4 && // code/schema-dense line
        !(s.contains('=') && SCHEMA_TOKEN.containsMatchIn(s)) // schema column def

/** Extract up to a few product-constraint sentences from a doc section body. */
private fun rulesIn(body: String): List<String> =
    body.split(SENTENCE)
        .map { it.trim().trim('-', '*', '•', '>', ' ', '\t').replace(WS, " ") }
        .filter(::isProseRule)
        .distinct()
        .take(3)

/** Promote a section heading to an alias, unless it's a generic section title. */
private fun aliasFromHeading(heading: String): String? {
    val h = heading.replace(LEAD_NUM, "").trim().trimEnd(':').trim()
    if (h.length > 50 || h.isBlank()) return null
    if (h.split(Regex("\\s+")).size > 6) return null
    if (h.lowercase() in GENERIC_HEADINGS) return null
    if (h.any { it == '`' || it == '|' || it == '#' || it == '(' }) return null
    return h
}

private fun buildVocabulary(concepts: List<Concept>): List<Term> = buildList {
    for (c in concepts) {
        val forms = LinkedHashSet<String>()
        forms += c.name
        forms += c.name.replace(CAMEL, "$1 $2") // DatabaseInstance -> Database Instance
        forms += c.aliases
        for (form in forms) {
            val t = form.trim()
            if (t.length < 3) continue
            add(Term(Regex("\\b" + Regex.escape(t) + "\\b", RegexOption.IGNORE_CASE), c.id))
        }
    }
}

private fun splitSections(content: String): List<Pair<String, String>> {
    val acc = ArrayList<Pair<String, StringBuilder>>()
    acc.add(INTRO to StringBuilder())
    for (line in content.lines()) {
        val m = HEADING.find(line)
        if (m != null) acc.add(m.groupValues[1].trim() to StringBuilder())
        else acc.last().second.append(line).append('\n')
    }
    return acc.map { it.first to it.second.toString().trim() }
        .filter { it.first != INTRO || it.second.isNotBlank() }
}

private fun walkDocs(dir: File): Sequence<File> = sequence {
    val entries = dir.listFiles() ?: return@sequence
    for (entry in entries) {
        if (entry.name in IGNORE) continue
        if (entry.isDirectory) {
            yieldAll(walkDocs(entry))
        } else if (entry.isFile && entry.extension.lowercase() in DOC_EXT && entry.length() <= 2_000_000) {
            yield(entry)
        }
    }
}

private fun slug(s: String): String =
    s.lowercase().replace(NON_SLUG, "-").trim('-').take(60).ifBlank { "section" }

// --- web source: fetch a URL, strip HTML to markdown-ish text (dependency-free) ---

private fun pageName(url: String): String =
    url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }

private fun fetchUrl(url: String): String? = runCatching {
    val client = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build()
    val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
        .header("User-Agent", "stele/0.1 (docs ingest)")
        .timeout(java.time.Duration.ofSeconds(30))
        .GET().build()
    val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() in 200..299) resp.body() else null
}.getOrNull()

private val SCRIPT_STYLE = Regex("(?is)<(script|style|noscript|head)[^>]*>.*?</\\1>")
private val H_TAG = Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1>")
private val LI_TAG = Regex("(?i)<li[^>]*>")
private val BLOCK_END = Regex("(?i)</(p|div|section|article|tr|li|h[1-6])>|<br\\s*/?>")
private val ANY_TAG = Regex("<[^>]+>")
private val MANY_NL = Regex("\\n{3,}")

/** Crude, dependency-free HTML → text: keep headings as markdown `#` so sections + concept matching work. */
private fun htmlToText(html: String): String {
    if (!html.contains('<')) return html // already plain text / markdown
    var s = SCRIPT_STYLE.replace(html, " ")
    s = H_TAG.replace(s) { m -> "\n" + "#".repeat(m.groupValues[1].toInt()) + " " + m.groupValues[2] + "\n" }
    s = LI_TAG.replace(s, "\n- ")
    s = BLOCK_END.replace(s, "\n")
    s = ANY_TAG.replace(s, "")
    s = decodeEntities(s)
    return MANY_NL.replace(s, "\n\n").trim()
}

private fun decodeEntities(s: String): String = s
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
    .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
