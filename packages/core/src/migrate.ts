import type { DB } from "./db";
import { SCHEMA, SCHEMA_VERSION } from "./schema";

/** Apply the schema (idempotent) and record the version. */
export function migrate(db: DB): void {
  db.exec(SCHEMA);
  db.prepare(
    `INSERT INTO schema_meta (key, value) VALUES ('version', ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
  ).run(SCHEMA_VERSION);
}

export function schemaVersion(db: DB): string | null {
  const row = db
    .prepare(`SELECT value FROM schema_meta WHERE key = 'version'`)
    .get() as { value: string } | undefined;
  return row?.value ?? null;
}
