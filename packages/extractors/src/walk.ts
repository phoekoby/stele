import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const IGNORE = new Set([
  "node_modules",
  ".git",
  "dist",
  "build",
  ".stele",
  ".next",
  "out",
  "target",
  "vendor",
  "__pycache__",
  ".venv",
  ".idea",
  ".gradle",
]);

const MAX_BYTES = 1_000_000; // skip files larger than 1 MB

/** Recursively yield source file paths, skipping vendor dirs and large files. */
export function* walk(dir: string): Generator<string> {
  for (const name of readdirSync(dir)) {
    if (IGNORE.has(name)) continue;
    const p = join(dir, name);
    let st;
    try {
      st = statSync(p);
    } catch {
      continue;
    }
    if (st.isDirectory()) yield* walk(p);
    else if (st.isFile() && st.size <= MAX_BYTES) yield p;
  }
}
