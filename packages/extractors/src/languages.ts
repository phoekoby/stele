import { createRequire } from "node:module";
import { dirname, join } from "node:path";

const require = createRequire(import.meta.url);

/** Directory of prebuilt grammar wasms inside tree-sitter-wasms. */
const WASM_DIR = join(
  dirname(require.resolve("tree-sitter-wasms/package.json")),
  "out",
);

/** File extension -> tree-sitter grammar name (must exist in tree-sitter-wasms/out). */
export const EXT_TO_LANG: Record<string, string> = {
  ".ts": "typescript",
  ".tsx": "tsx",
  ".js": "javascript",
  ".jsx": "javascript",
  ".mjs": "javascript",
  ".cjs": "javascript",
  ".py": "python",
  ".go": "go",
  ".rs": "rust",
  ".java": "java",
  ".kt": "kotlin",
  ".kts": "kotlin",
  ".rb": "ruby",
  ".php": "php",
  ".swift": "swift",
  ".scala": "scala",
  ".cs": "c_sharp",
  ".c": "c",
  ".h": "c",
  ".cpp": "cpp",
  ".cc": "cpp",
  ".hpp": "cpp",
  ".lua": "lua",
  ".vue": "vue",
  ".css": "css",
  ".html": "html",
  ".json": "json",
  ".yaml": "yaml",
  ".yml": "yaml",
  ".toml": "toml",
};

export const wasmPath = (lang: string): string =>
  join(WASM_DIR, `tree-sitter-${lang}.wasm`);
