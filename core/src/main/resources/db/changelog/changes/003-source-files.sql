--liquibase formatted sql

-- Per-file provenance for incremental re-index + staleness: the on-disk mtime
-- each source file had when its symbols were last extracted. A file whose
-- current mtime differs has changed since indexing (its symbols may be stale).

--changeset stele:3 splitStatements:true endDelimiter:;
CREATE TABLE IF NOT EXISTS source_files (
  path  TEXT PRIMARY KEY,
  mtime INTEGER NOT NULL
);
