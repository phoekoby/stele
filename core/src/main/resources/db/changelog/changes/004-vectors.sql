--liquibase formatted sql

-- Semantic layer: pre-computed embeddings for concept cards and doc sections,
-- stored as little-endian float32 BLOBs. Computed once at `stele embed` (part of
-- `sync`) so SERVING never needs a live embedding model: resolve/drill read these
-- and rank by cosine in-process; with no vectors the store falls back to lexical.
-- `model` records provenance — vectors from a different model are stale.

--changeset stele:4 splitStatements:true endDelimiter:;
CREATE TABLE IF NOT EXISTS concept_vectors (
  concept_id TEXT PRIMARY KEY REFERENCES concepts(id) ON DELETE CASCADE,
  model      TEXT NOT NULL,
  vec        BLOB NOT NULL
);

CREATE TABLE IF NOT EXISTS section_vectors (
  artifact_id TEXT PRIMARY KEY REFERENCES artifacts(id) ON DELETE CASCADE,
  model       TEXT NOT NULL,
  vec         BLOB NOT NULL
);
