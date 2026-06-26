/**
 * Stele graph schema (Phase 0).
 *
 * Three layers hang off the concept spine:
 *   concepts  — the ubiquitous-language ontology (the spine / IP)
 *   artifacts — code / design / product / evidence / config nodes
 *   mentions  — raw term occurrences inside an artifact (pre-resolution)
 *   edges     — typed, provenance-weighted links (concept<->artifact, etc.)
 *
 * Embeddings + vector tables are added in Phase 3 (sqlite-vec).
 */
export const SCHEMA_VERSION = "1";

export const SCHEMA = /* sql */ `
CREATE TABLE IF NOT EXISTS schema_meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS concepts (
  id              TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  definition      TEXT,
  bounded_context TEXT,
  aliases_json    TEXT NOT NULL DEFAULT '[]',
  status          TEXT NOT NULL DEFAULT 'candidate',   -- candidate | confirmed
  created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS artifacts (
  id          TEXT PRIMARY KEY,
  kind        TEXT NOT NULL,        -- code_symbol|file|frame|component|issue|pr|commit|doc|config
  layer       TEXT NOT NULL,        -- code|design|product|evidence|config
  source      TEXT NOT NULL,        -- connector id: git|github|figma|docs|config
  ref         TEXT NOT NULL,        -- stable locator: 'path#symbol', 'issue:org/repo#377'
  title       TEXT,
  body        TEXT,
  attrs_json  TEXT NOT NULL DEFAULT '{}',
  created_at  TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (source, ref)
);

CREATE TABLE IF NOT EXISTS mentions (
  id          TEXT PRIMARY KEY,
  artifact_id TEXT NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,
  term        TEXT NOT NULL,
  normalized  TEXT NOT NULL,
  span        TEXT,
  created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS edges (
  id            TEXT PRIMARY KEY,
  src_id        TEXT NOT NULL,      -- concept or artifact id
  dst_id        TEXT NOT NULL,      -- concept or artifact id
  type          TEXT NOT NULL,      -- implements|describes|depicts|constrains|changed|references|belongs_to|relates
  source        TEXT NOT NULL,      -- deterministic|inferred|human
  confidence    REAL NOT NULL DEFAULT 1.0,
  evidence_json TEXT NOT NULL DEFAULT '[]',
  status        TEXT NOT NULL DEFAULT 'proposed',      -- proposed|confirmed|rejected
  valid_from    TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (src_id, dst_id, type)
);

CREATE INDEX IF NOT EXISTS idx_mentions_norm     ON mentions(normalized);
CREATE INDEX IF NOT EXISTS idx_mentions_artifact ON mentions(artifact_id);
CREATE INDEX IF NOT EXISTS idx_edges_src         ON edges(src_id);
CREATE INDEX IF NOT EXISTS idx_edges_dst         ON edges(dst_id);
CREATE INDEX IF NOT EXISTS idx_edges_type        ON edges(type);
CREATE INDEX IF NOT EXISTS idx_artifacts_layer   ON artifacts(layer);
`;
