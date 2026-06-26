import { readFileSync } from "node:fs";
import { extname, relative, resolve } from "node:path";
import Parser from "web-tree-sitter";
import type { GraphStore } from "@stele/core";
import { EXT_TO_LANG, wasmPath } from "./languages";
import { normalize } from "./normalize";
import { walk } from "./walk";

/** Minimal structural view of a tree-sitter node (decoupled from lib type names). */
interface TSNode {
  type: string;
  text: string;
  childCount: number;
  child(index: number): TSNode | null;
  startPosition: { row: number; column: number };
}

const IDENT = /identifier/;
const COMMENT = /comment/;
const STRING = /string/;

export interface IngestResult {
  files: number;
  mentions: number;
  /** Languages whose grammar wasm failed to load (ABI mismatch) and were skipped. */
  skippedLangs: string[];
}

export async function ingestCode(
  store: GraphStore,
  rootArg: string,
): Promise<IngestResult> {
  const root = resolve(rootArg);
  await Parser.init();
  const parser = new Parser();
  const langCache = new Map<string, Parser.Language | null>();
  const skipped = new Set<string>();
  let files = 0;
  let mentions = 0;

  for (const file of walk(root)) {
    const lang = EXT_TO_LANG[extname(file)];
    if (!lang) continue;

    let language = langCache.get(lang);
    if (language === undefined) {
      try {
        language = await Parser.Language.load(wasmPath(lang));
      } catch (err) {
        language = null;
        skipped.add(lang);
        console.warn(
          `[stele] grammar "${lang}" skipped (incompatible): ${(err as Error).message}`,
        );
      }
      langCache.set(lang, language);
    }
    if (!language) continue;
    parser.setLanguage(language);

    const src = readFileSync(file, "utf8");
    const tree = parser.parse(src);
    if (!tree) continue;

    const ref = relative(root, file).split("\\").join("/");
    const fileArtifact = store.addArtifact({
      kind: "file",
      layer: "code",
      source: "code",
      ref,
      title: ref,
    });
    store.clearMentions(fileArtifact);
    files++;

    const seen = new Set<string>();
    const stack: TSNode[] = [tree.rootNode as unknown as TSNode];
    while (stack.length > 0) {
      const node = stack.pop()!;
      const t = node.type;

      let term: string | null = null;
      if (IDENT.test(t)) {
        term = node.text;
      } else if (COMMENT.test(t) || STRING.test(t)) {
        term = node.text.replace(/["'`]/g, "").trim();
      }

      if (term && term.length > 1 && term.length <= 80) {
        const norm = normalize(term);
        const key = `${t}|${norm}`;
        if (norm && !seen.has(key)) {
          seen.add(key);
          store.addMention({
            artifact_id: fileArtifact,
            term,
            normalized: norm,
            span: String(node.startPosition.row + 1),
          });
          mentions++;
        }
      }

      for (let i = 0; i < node.childCount; i++) {
        const child = node.child(i);
        if (child) stack.push(child);
      }
    }

    (tree as { delete?: () => void }).delete?.();
  }

  return { files, mentions, skippedLangs: [...skipped] };
}
