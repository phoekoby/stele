package dev.stele.core.store

import dev.stele.core.model.Artifact
import dev.stele.core.model.ArtifactKind
import dev.stele.core.model.CallEdge
import dev.stele.core.model.CodeContextEntry
import dev.stele.core.model.RuleCandidate
import dev.stele.core.model.Concept
import dev.stele.core.model.ConceptCandidate
import dev.stele.core.model.ConceptStatus
import dev.stele.core.model.DedupeResult
import dev.stele.core.model.EdgeView
import dev.stele.core.model.EdgeSource
import dev.stele.core.model.EdgeStatus
import dev.stele.core.model.EdgeType
import dev.stele.core.model.GraphCounts
import dev.stele.core.model.Layer
import dev.stele.core.model.TermCount
import java.sql.Connection
import java.util.UUID

/**
 * Thin data-access layer over the graph tables — the swappable graph-store seam.
 * Phase 1 exercises [counts], [addArtifact], [addMention], [clearMentions] and
 * [topTerms]; the rest are here so connectors/resolver build against a stable API.
 */
class GraphStore(private val conn: Connection) {

    fun counts(): GraphCounts {
        fun n(table: String): Int =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) AS n FROM $table").use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        return GraphCounts(n("concepts"), n("artifacts"), n("mentions"), n("edges"))
    }

    /** Upsert an artifact, keyed by (source, ref). Returns its id. */
    fun addArtifact(
        kind: ArtifactKind,
        layer: Layer,
        source: String,
        ref: String,
        title: String? = null,
        body: String? = null,
        attrs: Map<String, Any?> = emptyMap(),
        id: String? = null,
    ): String {
        val newId = id ?: UUID.randomUUID().toString()
        conn.prepareStatement(
            """
            INSERT INTO artifacts (id, kind, layer, source, ref, title, body, attrs_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source, ref) DO UPDATE SET
              title = excluded.title, body = excluded.body, attrs_json = excluded.attrs_json
            """.trimIndent(),
        ).use {
            it.setString(1, newId)
            it.setString(2, kind.value)
            it.setString(3, layer.value)
            it.setString(4, source)
            it.setString(5, ref)
            it.setString(6, title)
            it.setString(7, body)
            it.setString(8, jsonEncode(attrs))
            it.executeUpdate()
        }
        return conn.prepareStatement("SELECT id FROM artifacts WHERE source = ? AND ref = ?").use {
            it.setString(1, source)
            it.setString(2, ref)
            it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    fun addMention(
        artifactId: String,
        term: String,
        normalized: String,
        span: String? = null,
        id: String? = null,
    ): String {
        val newId = id ?: UUID.randomUUID().toString()
        conn.prepareStatement(
            "INSERT INTO mentions (id, artifact_id, term, normalized, span) VALUES (?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, newId)
            it.setString(2, artifactId)
            it.setString(3, term)
            it.setString(4, normalized)
            it.setString(5, span)
            it.executeUpdate()
        }
        return newId
    }

    fun addConcept(
        name: String,
        definition: String? = null,
        boundedContext: String? = null,
        aliases: List<String> = emptyList(),
        status: ConceptStatus = ConceptStatus.CANDIDATE,
        id: String? = null,
    ): String {
        val newId = id ?: UUID.randomUUID().toString()
        conn.prepareStatement(
            "INSERT INTO concepts (id, name, definition, bounded_context, aliases_json, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, newId)
            it.setString(2, name)
            it.setString(3, definition)
            it.setString(4, boundedContext)
            it.setString(5, jsonEncode(aliases))
            it.setString(6, status.value)
            it.executeUpdate()
        }
        return newId
    }

    /** Upsert a typed edge, keyed by (src_id, dst_id, type). */
    fun addEdge(
        srcId: String,
        dstId: String,
        type: EdgeType,
        source: EdgeSource,
        confidence: Double = 1.0,
        evidence: List<String> = emptyList(),
        status: EdgeStatus = EdgeStatus.PROPOSED,
        id: String? = null,
    ): String {
        val newId = id ?: UUID.randomUUID().toString()
        conn.prepareStatement(
            """
            INSERT INTO edges (id, src_id, dst_id, type, source, confidence, evidence_json, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(src_id, dst_id, type) DO UPDATE SET
              confidence = excluded.confidence,
              evidence_json = excluded.evidence_json,
              status = excluded.status
            """.trimIndent(),
        ).use {
            it.setString(1, newId)
            it.setString(2, srcId)
            it.setString(3, dstId)
            it.setString(4, type.value)
            it.setString(5, source.value)
            it.setDouble(6, confidence)
            it.setString(7, jsonEncode(evidence))
            it.setString(8, status.value)
            it.executeUpdate()
        }
        return newId
    }

    /** Remove all mentions of an artifact (for idempotent re-ingest). */
    fun clearMentions(artifactId: String) {
        conn.prepareStatement("DELETE FROM mentions WHERE artifact_id = ?").use {
            it.setString(1, artifactId)
            it.executeUpdate()
        }
    }

    /** Id of a concept by name (for idempotent find-or-create), or null. */
    fun findConceptId(name: String): String? =
        conn.prepareStatement("SELECT id FROM concepts WHERE name = ? LIMIT 1").use { st ->
            st.setString(1, name)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun getConceptByName(name: String): Concept? =
        conn.prepareStatement(
            "SELECT id, name, definition, bounded_context, status FROM concepts WHERE name = ? LIMIT 1",
        ).use { st ->
            st.setString(1, name)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                Concept(
                    id = rs.getString("id"),
                    name = rs.getString("name"),
                    definition = rs.getString("definition"),
                    boundedContext = rs.getString("bounded_context"),
                    status = ConceptStatus.entries.firstOrNull { it.value == rs.getString("status") }
                        ?: ConceptStatus.CANDIDATE,
                )
            }
        }

    /** Candidate concepts with sample symbol/dir evidence, for canonicalization. */
    fun candidateConcepts(maxSymbols: Int = 12): List<ConceptCandidate> {
        val byConcept = LinkedHashMap<String, Triple<String, MutableList<String>, MutableSet<String>>>()
        conn.prepareStatement(
            """
            SELECT cc.id AS cid, cc.name AS cname, a.ref AS ref, a.title AS title
            FROM concepts cc
            JOIN edges e ON e.dst_id = cc.id AND e.type = 'implements'
            JOIN artifacts a ON a.id = e.src_id
            WHERE cc.status = 'candidate' AND cc.definition IS NULL
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val cid = rs.getString("cid")
                    val entry = byConcept.getOrPut(cid) {
                        Triple(rs.getString("cname"), mutableListOf(), linkedSetOf())
                    }
                    if (entry.second.size < maxSymbols) entry.second.add(rs.getString("title"))
                    val ref = rs.getString("ref")
                    if (entry.third.size < 4) entry.third.add(ref.substringBeforeLast('/', ""))
                }
            }
        }
        return byConcept.map { (id, t) -> ConceptCandidate(id, t.first, t.second, t.third.toList()) }
    }

    fun updateConcept(
        id: String,
        name: String,
        definition: String?,
        boundedContext: String?,
        aliases: List<String>,
        status: ConceptStatus? = null,
    ) {
        conn.prepareStatement(
            "UPDATE concepts SET name = ?, definition = ?, bounded_context = ?, aliases_json = ?" +
                (if (status != null) ", status = ?" else "") + " WHERE id = ?",
        ).use {
            it.setString(1, name)
            it.setString(2, definition)
            it.setString(3, boundedContext)
            it.setString(4, jsonEncode(aliases))
            if (status != null) {
                it.setString(5, status.value); it.setString(6, id)
            } else {
                it.setString(5, id)
            }
            it.executeUpdate()
        }
    }

    /**
     * Merge concepts that share a name (folder twins like Users+User after
     * canonicalization). Keeps the best one (has-definition, then most edges),
     * repoints `implements` edges onto it, folds the others' names into its
     * aliases, and deletes them. Deterministic — no LLM.
     */
    fun dedupeByName(): DedupeResult {
        val all = conn.prepareStatement(
            """
            SELECT cc.id AS id, cc.name AS name, cc.definition AS def, cc.aliases_json AS aliases,
                   COUNT(e.id) AS n
            FROM concepts cc
            LEFT JOIN edges e ON e.dst_id = cc.id AND e.type = 'implements'
            GROUP BY cc.id
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ConceptRow(
                                rs.getString("id"), rs.getString("name"),
                                rs.getString("def") != null, rs.getString("aliases") ?: "[]", rs.getInt("n"),
                            ),
                        )
                    }
                }
            }
        }

        // Group by a normalized key so case + singular/plural twins collapse (Secret/Secrets, Tag/Tags).
        val groups = all.groupBy { normKey(it.name) }.filterValues { it.size > 1 }
        var merged = 0
        for ((_, group) in groups) {
            val sorted = group.sortedWith(
                compareByDescending<ConceptRow> { it.hasDef }.thenByDescending { it.edges }.thenBy { it.name.length },
            )
            val keep = sorted.first()
            val aliases = LinkedHashSet(parseStringArray(keep.aliasesJson))
            for (drop in sorted.drop(1)) {
                // Repoint ALL edges (not only implements) so the survivor inherits the twin's docs/rules/relations.
                for (col in listOf("dst_id", "src_id")) {
                    conn.prepareStatement("UPDATE OR IGNORE edges SET $col = ? WHERE $col = ?").use {
                        it.setString(1, keep.id); it.setString(2, drop.id); it.executeUpdate()
                    }
                }
                aliases += parseStringArray(drop.aliasesJson)
                aliases += drop.name
                deleteConcept(drop.id) // drops edges that collided (OR IGNORE skipped) + the concept row
                merged++
            }
            aliases.remove(keep.name)
            conn.prepareStatement("UPDATE concepts SET aliases_json = ? WHERE id = ?").use {
                it.setString(1, jsonEncode(aliases.toList())); it.setString(2, keep.id); it.executeUpdate()
            }
        }
        // Remove self-loops a merge may have created (e.g. survivor —relates→ its twin).
        conn.prepareStatement("DELETE FROM edges WHERE src_id = dst_id").use { it.executeUpdate() }
        return DedupeResult(merged, groups.size)
    }

    /** Normalize a concept name for dedup: lowercase + naive English singularization (keeps trailing `ss`). */
    private fun normKey(name: String): String {
        val n = name.trim().lowercase()
        return when {
            n.length <= 3 || n.endsWith("ss") -> n
            n.endsWith("ies") -> n.dropLast(3) + "y"
            n.endsWith("ses") || n.endsWith("xes") || n.endsWith("zes") ||
                n.endsWith("ches") || n.endsWith("shes") -> n.dropLast(2)
            n.endsWith("s") -> n.dropLast(1)
            else -> n
        }
    }

    /** Drop a concept the canonicalizer rejected, along with edges touching it. */
    fun deleteConcept(id: String) {
        conn.prepareStatement("DELETE FROM edges WHERE src_id = ? OR dst_id = ?").use {
            it.setString(1, id); it.setString(2, id); it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM concepts WHERE id = ?").use {
            it.setString(1, id); it.executeUpdate()
        }
    }

    private fun rowToConcept(rs: java.sql.ResultSet): Concept = Concept(
        id = rs.getString("id"),
        name = rs.getString("name"),
        definition = rs.getString("definition"),
        boundedContext = rs.getString("bounded_context"),
        aliases = parseStringArray(rs.getString("aliases_json") ?: "[]"),
        status = ConceptStatus.entries.firstOrNull { it.value == rs.getString("status") } ?: ConceptStatus.CANDIDATE,
    )

    private fun rowToArtifact(rs: java.sql.ResultSet): Artifact = Artifact(
        id = rs.getString("id"),
        kind = ArtifactKind.entries.firstOrNull { it.value == rs.getString("kind") } ?: ArtifactKind.FILE,
        layer = Layer.entries.firstOrNull { it.value == rs.getString("layer") } ?: Layer.CODE,
        source = rs.getString("source"),
        ref = rs.getString("ref"),
        title = rs.getString("title"),
        body = rs.getString("body"),
    )

    // ---- Phase 5: human-in-the-loop review ----

    /** Proposed edges for review, strongest first, with resolved endpoint labels. */
    fun proposedEdges(type: String? = null, limit: Int = 50): List<EdgeView> {
        val sql = buildString {
            append("SELECT id, src_id, dst_id, type, confidence, evidence_json, status FROM edges WHERE status = 'proposed'")
            if (type != null) append(" AND type = ?")
            append(" ORDER BY confidence DESC LIMIT ?")
        }
        return conn.prepareStatement(sql).use { st ->
            var i = 1
            if (type != null) st.setString(i++, type)
            st.setInt(i, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            EdgeView(
                                id = rs.getString("id"),
                                type = rs.getString("type"),
                                src = labelOf(rs.getString("src_id")),
                                dst = labelOf(rs.getString("dst_id")),
                                confidence = rs.getDouble("confidence"),
                                evidence = parseStringArray(rs.getString("evidence_json") ?: "[]"),
                                status = rs.getString("status"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Human-readable label for any node id (concept name or artifact). */
    fun labelOf(id: String): String {
        conn.prepareStatement("SELECT name FROM concepts WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) return "concept ${rs.getString(1)}" }
        }
        conn.prepareStatement("SELECT kind, title, ref FROM artifacts WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) return "${rs.getString("kind")} ${rs.getString("title") ?: rs.getString("ref")}" }
        }
        return id
    }

    fun setEdgeStatus(id: String, status: EdgeStatus): Boolean =
        conn.prepareStatement("UPDATE edges SET status = ? WHERE id = ?").use {
            it.setString(1, status.value); it.setString(2, id); it.executeUpdate() > 0
        }

    /** Replace a rule artifact's title with the refined invariant text. */
    fun updateArtifactTitle(id: String, title: String) {
        conn.prepareStatement("UPDATE artifacts SET title = ? WHERE id = ?").use {
            it.setString(1, title); it.setString(2, id); it.executeUpdate()
        }
    }

    /** Scraped product-rule candidates: each proposed `constrains` edge with its rule text + concept. */
    fun candidateRules(limit: Int = 1000): List<RuleCandidate> =
        conn.prepareStatement(
            """
            SELECT e.id AS eid, a.id AS aid, COALESCE(a.body, a.title) AS txt, cc.id AS cid, cc.name AS cname
            FROM edges e
            JOIN artifacts a ON a.id = e.src_id AND a.kind = 'rule'
            JOIN concepts cc ON cc.id = e.dst_id
            WHERE e.type = 'constrains' AND e.status = 'proposed'
            ORDER BY cc.name, a.ref LIMIT ?
            """.trimIndent(),
        ).use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            RuleCandidate(
                                rs.getString("eid"), rs.getString("aid"),
                                rs.getString("txt") ?: "", rs.getString("cid"), rs.getString("cname"),
                            ),
                        )
                    }
                }
            }
        }

    /** Bulk-confirm proposed edges (optionally one type) with confidence >= [minConfidence]. */
    fun confirmAbove(type: String?, minConfidence: Double): Int {
        val sql = buildString {
            append("UPDATE edges SET status = 'confirmed' WHERE status = 'proposed' AND confidence >= ?")
            if (type != null) append(" AND type = ?")
        }
        return conn.prepareStatement(sql).use { st ->
            st.setDouble(1, minConfidence)
            if (type != null) st.setString(2, type)
            st.executeUpdate()
        }
    }

    fun edgeStatusCounts(): List<Pair<String, Int>> =
        conn.prepareStatement("SELECT status, COUNT(*) AS n FROM edges GROUP BY status ORDER BY n DESC").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("status") to rs.getInt("n")) } }
        }

    fun edgeTypeCounts(): List<Pair<String, Int>> =
        conn.prepareStatement("SELECT type, COUNT(*) AS n FROM edges GROUP BY type ORDER BY n DESC").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("type") to rs.getInt("n")) } }
        }

    /** Add product-language aliases to a concept (dedup vs name + existing). Returns how many were new. */
    fun addAliases(conceptId: String, extra: Collection<String>): Int {
        val row = conn.prepareStatement("SELECT name, aliases_json FROM concepts WHERE id = ?").use { st ->
            st.setString(1, conceptId)
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getString("name") to (rs.getString("aliases_json") ?: "[]") else return 0
            }
        }
        val (name, aliasesJson) = row
        val current = LinkedHashSet(parseStringArray(aliasesJson))
        val seen = (current.map { it.lowercase() } + name.lowercase()).toHashSet()
        var added = 0
        for (alias in extra) {
            if (alias.lowercase() !in seen) {
                current.add(alias); seen.add(alias.lowercase()); added++
            }
        }
        if (added > 0) {
            conn.prepareStatement("UPDATE concepts SET aliases_json = ? WHERE id = ?").use {
                it.setString(1, jsonEncode(current.toList())); it.setString(2, conceptId); it.executeUpdate()
            }
        }
        return added
    }

    /** All concepts with their aliases — the ubiquitous-language vocabulary for doc matching. */
    fun conceptVocabulary(): List<Concept> =
        conn.prepareStatement(
            "SELECT id, name, definition, bounded_context, aliases_json, status FROM concepts",
        ).use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rowToConcept(rs)) } }
        }

    /**
     * Serving quality gate: what an agent is allowed to see. A human-`confirmed`
     * edge always passes; an unconfirmed `proposed` edge passes only above a
     * per-type confidence bar. This is what keeps the noisy deterministic-first
     * proposals (body-mention `describes`, weak co-occurrence `relates`) out of
     * agent context until a human confirms them. `rejected` never passes.
     */
    private object Gate {
        const val DESCRIBES_MIN = 0.9
        const val RELATES_MIN = 0.7
        const val IMPLEMENTS_MIN = 0.0
        const val CONSTRAINS_MIN = 0.0
    }

    private fun served(minConfidence: Double): String =
        "(e.status = 'confirmed' OR (e.status = 'proposed' AND e.confidence >= $minConfidence))"

    /** A concept is "resolved" once canonicalization gave it a definition; only those are served as neighbors. */
    private val RESOLVED = "COALESCE(TRIM(cc.definition), '') <> ''"

    /** Concepts linked to this one by a `relates` edge (either direction), strongest first. */
    fun relatedConcepts(conceptId: String): List<Concept> {
        val cols = "cc.id AS id, cc.name AS name, cc.definition AS definition, " +
            "cc.bounded_context AS bounded_context, cc.aliases_json AS aliases_json, cc.status AS status"
        return conn.prepareStatement(
            """
            SELECT $cols, e.confidence AS conf FROM edges e JOIN concepts cc ON cc.id = e.dst_id
            WHERE e.type = 'relates' AND e.src_id = ? AND ${served(Gate.RELATES_MIN)} AND $RESOLVED
            UNION
            SELECT $cols, e.confidence AS conf FROM edges e JOIN concepts cc ON cc.id = e.src_id
            WHERE e.type = 'relates' AND e.dst_id = ? AND ${served(Gate.RELATES_MIN)} AND $RESOLVED
            ORDER BY conf DESC
            """.trimIndent(),
        ).use { st ->
            st.setString(1, conceptId)
            st.setString(2, conceptId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rowToConcept(rs)) } }
        }
    }

    /** Product rules that constrain a concept (`constrains` edge from a `rule` artifact). */
    fun rulesFor(conceptId: String): List<Artifact> =
        conn.prepareStatement(
            """
            SELECT a.id, a.kind, a.layer, a.source, a.ref, a.title, a.body
            FROM edges e JOIN artifacts a ON a.id = e.src_id
            WHERE e.dst_id = ? AND e.type = 'constrains' AND a.kind = 'rule' AND ${served(Gate.CONSTRAINS_MIN)}
            ORDER BY a.ref
            """.trimIndent(),
        ).use { st ->
            st.setString(1, conceptId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rowToArtifact(rs)) } }
        }

    /** Product docs that describe a concept (`describes` edge), strongest first. */
    fun describingDocs(conceptId: String): List<Artifact> =
        conn.prepareStatement(
            """
            SELECT a.id, a.kind, a.layer, a.source, a.ref, a.title, a.body
            FROM edges e JOIN artifacts a ON a.id = e.src_id
            WHERE e.dst_id = ? AND e.type = 'describes' AND a.kind = 'doc' AND ${served(Gate.DESCRIBES_MIN)}
            ORDER BY e.confidence DESC, a.ref
            """.trimIndent(),
        ).use { st ->
            st.setString(1, conceptId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rowToArtifact(rs)) } }
        }

    /** Resolve a concept by exact name, then by alias (for MCP/agent queries). */
    fun resolveConcept(term: String): Concept? {
        val cols = "id, name, definition, bounded_context, aliases_json, status"
        fun read(where: String, bind: String): Concept? =
            conn.prepareStatement("SELECT $cols FROM concepts WHERE $where LIMIT 1").use { st ->
                st.setString(1, bind)
                st.executeQuery().use { rs -> if (rs.next()) rowToConcept(rs) else null }
            }
        return read("name = ?", term) ?: read("aliases_json LIKE ?", "%\"$term\"%")
    }

    /**
     * Everything a file/path touches: for each concept its code implements, the
     * concept + its rules + related concepts + docs + the symbols in this path.
     * This is what `context_for_code` serves to an agent during editing.
     */
    fun codeContext(path: String): List<CodeContextEntry> =
        conceptsForPath(path).map { (concept, symbols) ->
            CodeContextEntry(
                concept = concept,
                symbols = symbols,
                rules = rulesFor(concept.id),
                related = relatedConcepts(concept.id),
                docs = describingDocs(concept.id),
            )
        }

    /** Reverse lookup: concepts whose code (under `path`) implements them, with the matching symbol names. */
    fun conceptsForPath(path: String): List<Pair<Concept, List<String>>> {
        val byConcept = LinkedHashMap<String, Pair<Concept, MutableList<String>>>()
        conn.prepareStatement(
            """
            SELECT cc.id AS id, cc.name AS name, cc.definition AS definition,
                   cc.bounded_context AS bounded_context, cc.aliases_json AS aliases_json,
                   cc.status AS status, a.title AS title
            FROM artifacts a
            JOIN edges e ON e.src_id = a.id AND e.type = 'implements'
            JOIN concepts cc ON cc.id = e.dst_id
            WHERE a.kind = 'code_symbol' AND (a.ref = ? OR a.ref LIKE ?) AND ${served(Gate.IMPLEMENTS_MIN)}
            ORDER BY cc.name
            """.trimIndent(),
        ).use { st ->
            st.setString(1, path)
            st.setString(2, "$path%")
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val entry = byConcept.getOrPut(rs.getString("id")) { rowToConcept(rs) to mutableListOf() }
                    entry.second.add(rs.getString("title"))
                }
            }
        }
        return byConcept.values.map { it.first to it.second.toList() }
    }

    /**
     * The `calls` neighborhood of a file/dir: what its symbols call (outgoing,
     * incl. internal) and who calls into it from elsewhere (incoming). This is
     * the impact view `context_for_code` shows so an agent sees blast radius.
     */
    fun callsForPath(path: String): Pair<List<CallEdge>, List<CallEdge>> {
        fun query(sql: String, vararg binds: String): List<CallEdge> =
            conn.prepareStatement(sql).use { st ->
                binds.forEachIndexed { i, b -> st.setString(i + 1, b) }
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(CallEdge(rs.getString("src"), rs.getString("dst"))) }
                }
            }
        val like = "$path%"
        val out = query(
            """
            SELECT s.ref AS src, d.ref AS dst FROM edges e
            JOIN artifacts s ON s.id = e.src_id JOIN artifacts d ON d.id = e.dst_id
            WHERE e.type = 'calls' AND e.status != 'rejected' AND (s.ref = ? OR s.ref LIKE ?)
            ORDER BY s.ref LIMIT 40
            """.trimIndent(),
            path, like,
        )
        val incoming = query(
            """
            SELECT s.ref AS src, d.ref AS dst FROM edges e
            JOIN artifacts s ON s.id = e.src_id JOIN artifacts d ON d.id = e.dst_id
            WHERE e.type = 'calls' AND e.status != 'rejected' AND (d.ref = ? OR d.ref LIKE ?)
              AND NOT (s.ref = ? OR s.ref LIKE ?)
            ORDER BY d.ref LIMIT 40
            """.trimIndent(),
            path, like, path, like,
        )
        return out to incoming
    }

    /** Code artifacts linked to a concept via an `implements` edge. */
    fun implementersOf(conceptId: String): List<Artifact> =
        conn.prepareStatement(
            """
            SELECT a.id, a.kind, a.layer, a.source, a.ref, a.title, a.body
            FROM edges e JOIN artifacts a ON a.id = e.src_id
            WHERE e.dst_id = ? AND e.type = 'implements' AND ${served(Gate.IMPLEMENTS_MIN)}
            ORDER BY a.ref
            """.trimIndent(),
        ).use { st ->
            st.setString(1, conceptId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rowToArtifact(rs)) } }
        }

    /** Most common normalized terms across all mentions. */
    fun topTerms(limit: Int = 30): List<TermCount> =
        conn.prepareStatement(
            "SELECT normalized, COUNT(*) AS n FROM mentions " +
                "GROUP BY normalized ORDER BY n DESC LIMIT ?",
        ).use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(TermCount(rs.getString("normalized"), rs.getInt("n"))) }
            }
        }
}

private data class ConceptRow(
    val id: String,
    val name: String,
    val hasDef: Boolean,
    val aliasesJson: String,
    val edges: Int,
)
