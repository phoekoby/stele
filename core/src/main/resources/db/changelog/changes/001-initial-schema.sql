--liquibase formatted sql

-- Core graph tables: the concept spine plus artifacts/mentions/edges hanging off it.
-- IF NOT EXISTS keeps this idempotent on databases created before Liquibase.

--changeset stele:1 splitStatements:true endDelimiter:;
CREATE TABLE IF NOT EXISTS concepts (
  id              TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  definition      TEXT,
  bounded_context TEXT,
  aliases_json    TEXT NOT NULL DEFAULT '[]',
  status          TEXT NOT NULL DEFAULT 'candidate',
  created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS artifacts (
  id          TEXT PRIMARY KEY,
  kind        TEXT NOT NULL,
  layer       TEXT NOT NULL,
  source      TEXT NOT NULL,
  ref         TEXT NOT NULL,
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
  src_id        TEXT NOT NULL,
  dst_id        TEXT NOT NULL,
  type          TEXT NOT NULL,
  source        TEXT NOT NULL,
  confidence    REAL NOT NULL DEFAULT 1.0,
  evidence_json TEXT NOT NULL DEFAULT '[]',
  status        TEXT NOT NULL DEFAULT 'proposed',
  valid_from    TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (src_id, dst_id, type)
);
