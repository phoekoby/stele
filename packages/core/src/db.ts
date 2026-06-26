import Database from "better-sqlite3";
import * as sqliteVec from "sqlite-vec";

export type DB = Database.Database;

/**
 * Open (or create) a Stele graph database.
 * sqlite-vec is loaded best-effort — it is only required from Phase 3
 * (vector search), so a load failure must NOT break `stele init`.
 */
export function openDb(path: string): DB {
  const db = new Database(path);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  try {
    sqliteVec.load(db);
  } catch (err) {
    console.warn(
      "[stele] sqlite-vec not loaded (fine for Phase 0):",
      (err as Error).message,
    );
  }
  return db;
}
