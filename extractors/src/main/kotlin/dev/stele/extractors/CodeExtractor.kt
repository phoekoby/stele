package dev.stele.extractors

import dev.stele.core.model.ArtifactKind
import dev.stele.core.model.Layer
import dev.stele.core.store.GraphStore
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import java.io.File

data class IngestResult(
    val files: Int,
    val mentions: Int,
    val skippedLangs: List<String>,
)

private val IDENT = Regex("identifier")
private val COMMENT = Regex("comment")
private val STRING = Regex("string")
private val QUOTES = Regex("[\"'`]")

/**
 * Parse every supported file under [rootArg] with tree-sitter and record its
 * identifiers / comments / string literals as `mentions` on a `file` artifact.
 * Idempotent: artifacts upsert by (source, ref), mentions are cleared per file.
 */
fun ingestCode(store: GraphStore, rootArg: String): IngestResult {
    val root = File(rootArg).absoluteFile.normalize()
    val rootPath = root.toPath()
    val parser = TSParser()
    val langCache = HashMap<String, TSLanguage?>()
    val skipped = LinkedHashSet<String>()
    var files = 0
    var mentions = 0

    for (file in walk(root)) {
        val ext = "." + file.extension
        val langKey = EXT_TO_LANG[ext] ?: continue

        val language = langCache.getOrPut(langKey) { loadLanguage(langKey, parser, skipped) } ?: continue
        parser.setLanguage(language)

        val src = file.readText()
        val bytes = src.toByteArray(Charsets.UTF_8)
        val tree = parser.parseString(null, src) ?: continue

        val ref = rootPath.relativize(file.toPath()).toString().replace('\\', '/')
        val fileArtifact = store.addArtifact(
            kind = ArtifactKind.FILE,
            layer = Layer.CODE,
            source = "code",
            ref = ref,
            title = ref,
        )
        store.clearMentions(fileArtifact)
        files++

        val seen = HashSet<String>()
        val stack = ArrayDeque<TSNode>()
        stack.addLast(tree.rootNode)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val type = node.type

            var term: String? = null
            if (IDENT.containsMatchIn(type)) {
                term = nodeText(bytes, node)
            } else if (COMMENT.containsMatchIn(type) || STRING.containsMatchIn(type)) {
                term = nodeText(bytes, node).replace(QUOTES, "").trim()
            }

            if (term != null && term.length > 1 && term.length <= 80) {
                val norm = normalize(term)
                    .split(" ")
                    .filter { it.isNotEmpty() && it !in STOPWORDS }
                    .joinToString(" ")
                val key = "$type|$norm"
                if (norm.isNotEmpty() && seen.add(key)) {
                    store.addMention(
                        artifactId = fileArtifact,
                        term = term,
                        normalized = norm,
                        span = (node.startPoint.row + 1).toString(),
                    )
                    mentions++
                }
            }

            for (i in 0 until node.childCount) {
                stack.addLast(node.getChild(i))
            }
        }
    }

    return IngestResult(files, mentions, skipped.toList())
}

internal fun loadLanguage(
    langKey: String,
    parser: TSParser,
    skipped: MutableSet<String>,
): TSLanguage? {
    val factory = GRAMMARS[langKey]
    if (factory == null) {
        skipped.add(langKey)
        return null
    }
    return try {
        val language = factory()
        if (!parser.setLanguage(language)) {
            skipped.add(langKey)
            System.err.println("[stele] grammar \"$langKey\" skipped (incompatible ABI)")
            null
        } else {
            language
        }
    } catch (err: Throwable) {
        skipped.add(langKey)
        System.err.println("[stele] grammar \"$langKey\" skipped (load failed): ${err.message}")
        null
    }
}

internal fun nodeText(bytes: ByteArray, node: TSNode): String {
    val start = node.startByte
    val end = node.endByte
    if (start < 0 || end > bytes.size || end < start) return ""
    return String(bytes, start, end - start, Charsets.UTF_8)
}
