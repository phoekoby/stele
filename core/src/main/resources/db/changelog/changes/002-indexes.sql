--liquibase formatted sql

-- Lookup/traversal indexes. Kept as a separate changeset from the tables so
-- index tuning evolves independently of the schema shape.

--changeset stele:2 splitStatements:true endDelimiter:;
CREATE INDEX IF NOT EXISTS idx_mentions_norm     ON mentions(normalized);
CREATE INDEX IF NOT EXISTS idx_mentions_artifact ON mentions(artifact_id);
CREATE INDEX IF NOT EXISTS idx_edges_src         ON edges(src_id);
CREATE INDEX IF NOT EXISTS idx_edges_dst         ON edges(dst_id);
CREATE INDEX IF NOT EXISTS idx_edges_type        ON edges(type);
CREATE INDEX IF NOT EXISTS idx_artifacts_layer   ON artifacts(layer);
CREATE INDEX IF NOT EXISTS idx_artifacts_kind    ON artifacts(kind);
