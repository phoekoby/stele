# Phase 1 — `stele ingest code` (детальный спек)

*Цель фазы: научить Stele вытаскивать «сырой» язык продукта из кода — идентификаторы, строки, комментарии — на любом языке, через tree-sitter. Концепты/эмбеддинги/Figma — это Фазы 2–4, здесь их нет.*

> **Стек: Kotlin/JVM (порт), монорепо.** Реализация — модуль `extractors/` (`extractors/src/main/kotlin/dev/stele/extractors/`), поверх `core/`. Парсер — **java-tree-sitter** (`io.github.bonede:tree-sitter` + `tree-sitter-<lang>`, нативы в jar). Ниже — Kotlin-маппинг; TS-листинги оставлены как псевдокод-референс алгоритма (язык-независимого).

## Kotlin-маппинг (что где)

| Файл | Роль |
|---|---|
| `extractors/Grammars.kt` | `EXT_TO_LANG` (ext→грамматика, code-only) + `GRAMMARS` (key→фабрика `TSLanguage`); ключ без фабрики (напр. `tsx`) → graceful skip |
| `extractors/Normalize.kt` | `splitIdentifier` / `normalize` (camelCase + `_ - . / :`, lowercase, дроп ≤1 и чисто-числовых) |
| `extractors/FileWalker.kt` + `Stopwords.kt` | обход с IGNORE-сетом (>1 MB skip) · стоп-слова |
| `extractors/CodeExtractor.kt` | `ingestCode(store, root): IngestResult(files, mentions, skippedLangs)` |
| `core/store/GraphStore.kt` | `addArtifact` (upsert по `(source,ref)`), `addMention`, `clearMentions`, `topTerms` |
| `core/db/Migrations.kt` + `resources/db/changelog/` | `migrate(conn)` через **Liquibase** (master XML → `changes/NNN-*.sql`); `schema: v<changeset>` из `DATABASECHANGELOG` |

Грамматики (15): kotlin, java, python, go, rust, c, cpp, c_sharp, ruby, javascript, typescript, php, swift, scala, lua. Текст узла берётся срезом UTF-8-байтов исходника по `node.startByte..endByte` (у `TSNode` нет `getText()`).

---

## Definition of Done

```bash
./gradlew :cli:installDist
cli/build/install/stele/bin/stele ingest code <path-to-repo>
cli/build/install/stele/bin/stele stats     # artifacts и mentions > 0
cli/build/install/stele/bin/stele terms      # топ нормализованных терминов домена
```

Самопроверка: `stele ingest code .` на самом репозитории Stele вытаскивает его же идентификаторы (термины вроде `graph store`, `ingest`, `schema`).

---

## Зависимости (проверены на актуальность)

- **`web-tree-sitter@^0.20.8`** — WASM-рантайм tree-sitter. Без node-gyp, кроссплатформенно.
- **`tree-sitter-wasms@^0.1.13`** — готовые грамматики (подтверждено: `kotlin, dart, typescript, tsx, javascript, python, go, java, rust, swift, scala, c, cpp, c_sharp, ruby, php, lua, html, css, json, yaml, toml, vue` и др.).

```bash
pnpm --filter @stele/extractors add web-tree-sitter tree-sitter-wasms
```

---

## Новый пакет `packages/extractors`

```
packages/extractors/
  package.json        # @stele/extractors; deps: web-tree-sitter, tree-sitter-wasms, @stele/core
  tsconfig.json       # extends ../../tsconfig.base.json (как у core)
  src/
    index.ts          # export { ingestCode }
    languages.ts      # расширение → грамматика + путь к wasm
    normalize.ts      # split camelCase/snake_case → нормализованные токены
    walk.ts           # обход файлов с игнор-листом
    code.ts           # сам экстрактор
```

### `languages.ts` — расширение → грамматика

```ts
import { createRequire } from "node:module";
import { dirname, join } from "node:path";

const require = createRequire(import.meta.url);
const WASM_DIR = join(dirname(require.resolve("tree-sitter-wasms/package.json")), "out");

export const EXT_TO_LANG: Record<string, string> = {
  ".ts": "typescript", ".tsx": "tsx", ".js": "javascript", ".jsx": "javascript",
  ".py": "python", ".go": "go", ".rs": "rust", ".java": "java",
  ".kt": "kotlin", ".kts": "kotlin", ".dart": "dart", ".rb": "ruby",
  ".php": "php", ".swift": "swift", ".scala": "scala", ".cs": "c_sharp",
  ".c": "c", ".h": "c", ".cpp": "cpp", ".cc": "cpp", ".hpp": "cpp",
  ".lua": "lua", ".vue": "vue", ".css": "css", ".html": "html",
  ".json": "json", ".yaml": "yaml", ".yml": "yaml", ".toml": "toml",
};

export const wasmPath = (lang: string): string => join(WASM_DIR, `tree-sitter-${lang}.wasm`);
```

### `normalize.ts` — разбор имён (ubiquitous language)

```ts
/** validateRefund -> ["validate","refund"]; REFUND_WINDOW -> ["refund","window"] */
export function splitIdentifier(id: string): string[] {
  return id
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")  // camelCase
    .replace(/[_\-./]+/g, " ")               // snake / kebab / path
    .toLowerCase()
    .split(/\s+/)
    .filter((t) => t.length > 1 && !/^\d+$/.test(t));
}

export const normalize = (term: string): string => splitIdentifier(term).join(" ");
```

### `walk.ts` — обход с игнором

```ts
import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const IGNORE = new Set([
  "node_modules", ".git", "dist", "build", ".stele", ".next",
  "out", "target", "vendor", "__pycache__", ".venv",
]);

export function* walk(dir: string): Generator<string> {
  for (const name of readdirSync(dir)) {
    if (IGNORE.has(name)) continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) yield* walk(p);
    else if (st.isFile() && st.size <= 1_000_000) yield p; // skip >1 MB
  }
}
```

### `code.ts` — экстрактор (ядро Фазы 1)

```ts
import { readFileSync } from "node:fs";
import { extname, relative, resolve } from "node:path";
import { Language, Parser } from "web-tree-sitter";
import type { GraphStore } from "@stele/core";
import { EXT_TO_LANG, wasmPath } from "./languages";
import { normalize } from "./normalize";
import { walk } from "./walk";

const IDENT = /identifier/;   // simple_identifier, type_identifier, property_identifier, ...
const COMMENT = /comment/;
const STRING = /string/;

export async function ingestCode(
  store: GraphStore,
  rootArg: string,
): Promise<{ files: number; mentions: number }> {
  const root = resolve(rootArg);
  await Parser.init();
  const parser = new Parser();
  const langCache = new Map<string, Language>();
  let files = 0, mentions = 0;

  for (const file of walk(root)) {
    const lang = EXT_TO_LANG[extname(file)];
    if (!lang) continue;

    let language = langCache.get(lang);
    if (!language) {
      language = await Language.load(wasmPath(lang));
      langCache.set(lang, language);
    }
    parser.setLanguage(language);

    const src = readFileSync(file, "utf8");
    const tree = parser.parse(src);
    if (!tree) continue;

    const ref = relative(root, file).replaceAll("\\", "/");
    const fileArtifact = store.addArtifact({
      kind: "file", layer: "code", source: "code", ref, title: ref,
    });
    store.clearMentions(fileArtifact); // idempotent re-ingest (см. ниже)
    files++;

    const seen = new Set<string>();
    const stack = [tree.rootNode];
    while (stack.length) {
      const node = stack.pop()!;
      const t = node.type;
      let term: string | null = null;
      if (IDENT.test(t)) term = node.text;
      else if (COMMENT.test(t) || STRING.test(t)) term = node.text.replace(/["'`]/g, "").trim();

      if (term && term.length > 1 && term.length <= 80) {
        const norm = normalize(term);
        const key = `${t}|${norm}`;
        if (norm && !seen.has(key)) {
          seen.add(key);
          store.addMention({
            artifact_id: fileArtifact, term, normalized: norm,
            span: String(node.startPosition.row + 1),
          });
          mentions++;
        }
      }
      for (let i = 0; i < node.childCount; i++) stack.push(node.child(i)!);
    }
    tree.delete?.();
  }
  return { files, mentions };
}
```

---

## Правки в существующих пакетах

### `packages/core` — две мелочи в `GraphStore`

```ts
clearMentions(artifactId: string): void {
  this.db.prepare(`DELETE FROM mentions WHERE artifact_id = ?`).run(artifactId);
}

topTerms(limit = 30): Array<{ normalized: string; n: number }> {
  return this.db.prepare(
    `SELECT normalized, COUNT(*) AS n FROM mentions
     GROUP BY normalized ORDER BY n DESC LIMIT ?`,
  ).all(limit) as Array<{ normalized: string; n: number }>;
}
```

### `apps/cli` — команды `ingest code` и `terms`

`package.json`: добавить `"@stele/extractors": "workspace:*"`.

```ts
import { ingestCode } from "@stele/extractors";

const ingest = program.command("ingest").description("Pull data from sources into the graph");

ingest
  .command("code <path>")
  .description("Parse a repo (any language) and extract code mentions")
  .action(async (path: string) => {
    const p = dbPath();
    if (!existsSync(p)) { console.error("Run `stele init` first."); process.exit(1); }
    const db = openDb(p);
    const res = await ingestCode(new GraphStore(db), path);
    db.close();
    console.log(`✓ ingested ${res.files} files, ${res.mentions} mentions`);
  });

program
  .command("terms")
  .description("Show the most common normalized terms")
  .action(() => {
    const p = dbPath();
    if (!existsSync(p)) { console.error("Run `stele init` first."); process.exit(1); }
    const db = openDb(p);
    console.table(new GraphStore(db).topTerms(30));
    db.close();
  });
```

---

## Грабли и решения (важно)

- **`Parser.init()` в Node.** Если упадёт «can't find tree-sitter.wasm», передай локатор:
  `await Parser.init({ locateFile: (f: string) => require.resolve(\`web-tree-sitter/\${f}\`) });`
- **Идемпотентность.** `addArtifact` апсертит по `(source, ref)`, поэтому файл не дублируется; `clearMentions(fileArtifact)` чистит прежние термины перед повторным ингестом.
- **Шум.** Игнорируем vendor-папки и файлы > 1 МБ; режем термины длиннее 80 символов; пропускаем чисто числовые токены.
- **Суб-токены.** Сейчас `normalized` — это «validate refund»; на Фазе 3 резолвер дополнительно разобьёт на под-токены для матчинга концептов. Хранить так уже достаточно.

---

## Что осознанно отложено

- **`code_symbol`-артефакты** (отдельные узлы для имён функций/классов) → Phase 1.1; для DoD хватает file-артефактов + mentions.
- Концепты, эмбеддинги, evidence-якоря, Figma, инференс → Фазы 2–4.

---

## Чек-лист реализации

1. `packages/extractors` (4 файла) + `package.json`/`tsconfig.json`.
2. `GraphStore.clearMentions` + `GraphStore.topTerms` в `core`.
3. Команды `ingest code` и `terms` в `cli` + dep `@stele/extractors`.
4. `pnpm install` (подтянет web-tree-sitter + грамматики).
5. `pnpm stele ingest code .` → `pnpm stele stats` → `pnpm stele terms`.
6. Глазами проверить: в топ-терминах видны доменные слова твоего тест-репо.

---

## Реализовано (статус)

**Kotlin-порт готов.** Парсер — java-tree-sitter (`io.github.bonede`), нативы (Win/Linux/macOS) лежат в jar — без C-сборки.
Покрытие (15 грамматик): Kotlin, TypeScript, JavaScript, Python, Java, Go, Rust, C/C++, C#, Ruby, PHP, Scala, Swift, Lua.
**TSX отключён** — у bonede нет TSX-биндинга → `tsx` в graceful-skip (как и любая грамматика, что не загрузилась).

Запуск:
```
./gradlew build            # компиляция всех модулей + тесты (Normalize, CodeExtractor)
./gradlew :cli:installDist
cli/build/install/stele/bin/stele ingest code .
cli/build/install/stele/bin/stele terms
```

Тесты (`:extractors`): `normalize("validateRefund") == "validate refund"`; `ingestCode` на temp-папке с `.kt`+`.py` даёт `mentions>0` и поднимает термин `refund`. Самопроверка на этом репо поднимает доменные термины (`graph store`, `clikt command`, `ingest`, …).

*Историческая TS-версия (`web-tree-sitter` + `tree-sitter-wasms`) удалена; алгоритм перенесён 1-в-1.*
