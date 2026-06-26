import { randomUUID } from "node:crypto";
import type { DB } from "./db";
import type {
  Artifact,
  Concept,
  Edge,
  GraphCounts,
  Mention,
} from "./types";

/**
 * Thin data-access layer over the graph tables.
 * Phase 0 only needs `counts()`; the insert helpers are here so the
 * connectors/extractors (Phase 1+) have a stable API to build against.
 */
export class GraphStore {
  constructor(private db: DB) {}

  counts(): GraphCounts {
    const n = (table: string): number =>
      (this.db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get() as {
        n: number;
      }).n;
    return {
      concepts: n("concepts"),
      artifacts: n("artifacts"),
      mentions: n("mentions"),
      edges: n("edges"),
    };
  }

  /** Upsert an artifact, keyed by (source, ref). Returns its id. */
  addArtifact(a: Omit<Artifact, "id"> & { id?: string }): string {
    const id = a.id ?? randomUUID();
    this.db
      .prepare(
        `INSERT INTO artifacts (id, kind, layer, source, ref, title, body, attrs_json)
         VALUES (@id, @kind, @layer, @source, @ref, @title, @body, @attrs_json)
         ON CONFLICT(source, ref) DO UPDATE SET
           title = excluded.title, body = excluded.body, attrs_json = excluded.attrs_json`,
      )
      .run({
        id,
        kind: a.kind,
        layer: a.layer,
        source: a.source,
        ref: a.ref,
        title: a.title ?? null,
        body: a.body ?? null,
        attrs_json: JSON.stringify(a.attrs ?? {}),
      });
    const row = this.db
      .prepare(`SELECT id FROM artifacts WHERE source = ? AND ref = ?`)
      .get(a.source, a.ref) as { id: string };
    return row.id;
  }

  addMention(m: Omit<Mention, "id"> & { id?: string }): string {
    const id = m.id ?? randomUUID();
    this.db
      .prepare(
        `INSERT INTO mentions (id, artifact_id, term, normalized, span)
         VALUES (?, ?, ?, ?, ?)`,
      )
      .run(id, m.artifact_id, m.term, m.normalized, m.span ?? null);
    return id;
  }

  addConcept(c: Omit<Concept, "id"> & { id?: string }): string {
    const id = c.id ?? randomUUID();
    this.db
      .prepare(
        `INSERT INTO concepts (id, name, definition, bounded_context, aliases_json, status)
         VALUES (?, ?, ?, ?, ?, ?)`,
      )
      .run(
        id,
        c.name,
        c.definition ?? null,
        c.bounded_context ?? null,
        JSON.stringify(c.aliases ?? []),
        c.status ?? "candidate",
      );
    return id;
  }

  /** Upsert a typed edge, keyed by (src_id, dst_id, type). */
  addEdge(e: Omit<Edge, "id"> & { id?: string }): string {
    const id = e.id ?? randomUUID();
    this.db
      .prepare(
        `INSERT INTO edges (id, src_id, dst_id, type, source, confidence, evidence_json, status)
         VALUES (@id, @src, @dst, @type, @source, @confidence, @evidence, @status)
         ON CONFLICT(src_id, dst_id, type) DO UPDATE SET
           confidence = excluded.confidence,
           evidence_json = excluded.evidence_json,
           status = excluded.status`,
      )
      .run({
        id,
        src: e.src_id,
        dst: e.dst_id,
        type: e.type,
        source: e.source,
        confidence: e.confidence ?? 1.0,
        evidence: JSON.stringify(e.evidence ?? []),
        status: e.status ?? "proposed",
      });
    return id;
  }

  /** Remove all mentions of an artifact (for idempotent re-ingest). */
  clearMentions(artifactId: string): void {
    this.db.prepare(`DELETE FROM mentions WHERE artifact_id = ?`).run(artifactId);
  }

  /** Most common normalized terms across all mentions. */
  topTerms(limit = 30): Array<{ normalized: string; n: number }> {
    return this.db
      .prepare(
        `SELECT normalized, COUNT(*) AS n FROM mentions
         GROUP BY normalized ORDER BY n DESC LIMIT ?`,
      )
      .all(limit) as Array<{ normalized: string; n: number }>;
  }
}
