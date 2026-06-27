# Use Stele in Claude Code

Connect Stele's concept graph (product rules + cross-language code map) to your own Claude Code, and
track how the agent uses it. ~5 minutes.

## 1. Install (one command)

From the Stele repo root — builds the binary and puts `stele` on your PATH:

```powershell
.\install.ps1          # Windows
```
```bash
./install.sh           # macOS / Linux / Git Bash
```

Open a new terminal (so PATH refreshes) and `stele --help` works. Needs a JDK 17+.
*(No published binary yet — a remote `irm <url>/install.ps1 | iex` one-liner works once the repo is on GitHub.)*

## 2. Index the repo you want to work in

From the **root of your project** (not Stele's):

```bash
stele=…/cli/build/install/stele/bin/stele
$stele init                          # creates ./.stele/graph.db
$stele ingest symbols .              # code → concepts (tree-sitter, no LLM)
$stele ingest docs .                 # product docs → rules, relations, language (no LLM)
$stele build-ontology --model llama3.1   # OPTIONAL: LLM names/defines concepts (local Ollama). Rules work without it.
$stele concept <SomeDomainTerm>      # sanity check: you should see rules + the code behind it
```

A repo with prose product docs (rules like "must/only/never") gets the most value. Pure deployment docs
yield little — see `eval/PROTOCOL.md`.

## 3. Register the MCP server — automatic

`stele init` already merged a clean **`stele`** server into the project's **`.mcp.json`** — the install's
launcher + this repo's graph by **absolute path** (`…/bin/stele.bat mcp --db <abs>/.stele/graph.db`),
keeping any other servers in the file. Claude Code auto-connects it on open — nothing to run. The agent
gets `concept_context`, `context_for_code`, and `why_code`.

The absolute `--db` path matters: the server reads the graph by path, so it works no matter what working
directory Claude Code launches it from. (Earlier versions required the cwd to be the repo and showed an
**empty server** when it wasn't — that's fixed.)

Prefer a global registration instead of `.mcp.json`? Use the `--db` form:

```bash
claude mcp add stele -- /abs/path/to/stele mcp --db /abs/path/to/repo/.stele/graph.db
```
*(`stele init --no-mcp` skips writing `.mcp.json`.)*

## 4. The skill — added automatically

`stele init` already dropped `.claude/skills/stele-context/SKILL.md` into the repo, so Claude Code uses
Stele on its own (pull `context_for_code` before editing, `concept_context` on domain language). Nothing
to do here. (`stele init --no-skill` opts out; for a machine-wide skill copy it to
`~/.claude/skills/stele-context/SKILL.md`.)

## 5. Use it — then check the metrics

Work normally in Claude Code. Every MCP tool call is logged locally to `.stele/usage.jsonl`. Then:

```bash
$stele usage
```
```
Stele MCP usage — 42 calls, 2026-06-20..2026-06-27
  by tool:          concept_context 20, context_for_code 18, why_code 4
  resolve hit-rate: 88% (37/42)
  context served:   64 KB (~16k tokens of curated context pulled, not discovered)
  avg latency:      9 ms
  top queries:      Auth 8, Secret 5, Document 4 …
```

- **calls / by tool** — how often, and which tool, the agent reached for Stele.
- **hit-rate** — how often a query resolved to real context (misses = un-indexed terms → ingest more / re-run `build-ontology`).
- **context served** — KB and an approximate token count of curated context the agent pulled instead of
  discovering by hand (a proxy for the exploration it saved). It is a proxy, not a measured token saving.
- **top queries** — what concepts/files the agent keeps asking about.

`usage.jsonl` is local, append-only, never networked. Delete it to reset.
