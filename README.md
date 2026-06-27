# Stele

**The product↔code Rosetta stone — give your AI coding agent a developer's full context.**

A *stele* is a stone slab carrying the same text in several scripts (the Rosetta Stone is one). Stele does that for software: **one navigable graph** that carries the meaning of your **code** and your **product docs** — and later your **design** — joined by a shared **concept layer** (your team's ubiquitous language). It runs locally (one SQLite file, zero infra) and serves that graph to AI agents over **MCP**.

---

## Why this exists (the impact)

AI coding agents are good at *what* the code is — structure, symbols, call graphs. Tools like **GitNexus** and **ast-index** do that well. But they don't know *why* the code exists: the **product rules**, the domain language, the design intent.

So an agent writes code that compiles and looks right but quietly breaks a product rule — *"a secret is only decrypted on the `reveal` endpoint"*, *"authorization is deny-by-default"*, *"a downgrade respects the grace period"* — because nobody told it the rules.

There are code-understanding tools. There are knowledge-graph tools. **Nothing combines code + product into one thing and hands it to the agent.** That's the gap Stele fills.

The payoff: an engineer (or a PM, in plain language) says *"add a refund check to the subscription downgrade flow."* Because Stele maps the words **refund / subscription / downgrade** → to concepts → to their **rules** → to the exact code across services, the agent does the right thing the first time.

---

## The idea (in three sentences)

1. The **concept spine** — your ubiquitous language as a graph — is the heart. Code and docs are **never linked directly**: each attaches to a shared **concept**, and the cross-modal link is a *path through that concept*. That's what makes it language-agnostic (Go + TS + Kotlin + anything) and able to work on a messy legacy codebase.
2. Retrieval is **ontology-first**: resolve a term → traverse the typed graph → drill to the artifact. Not flat vector search.
3. Built **deterministically first**, an **LLM** canonicalizes on top, then a **human confirms** — that curated, confirmed set is the moat.

---

## How it works (the pipeline)

```
  your repo ─┬─ ingest symbols ───┐  tree-sitter: declarations → code symbols, clustered by feature
             ├─ ingest astindex ──┤  or a REAL resolved graph: ast-index (SQLite) / GitNexus (calls, imports)
             │                    ▼
             │            candidate concepts ──►  build-ontology   (LLM: canonical name + definition +
             │                                                      bounded-context; drops infra noise)
             │                                          │
             ├─ ingest docs ──────────────────────────►│  Markdown → product language, concept↔concept
             │   describes · rules · aliases · relations│  links, and RULES as first-class nodes
             │                                          ▼
             │                                       review    (human confirm / reject → curated graph;
             │                                          │        rejected drops from what the agent sees)
             ▼                                          ▼
                            .stele/graph.db  ──────►  stele mcp  ──────►  your agent
                            (one local SQLite file)    (MCP tools)        (Cursor / Claude Code)
```

One graph, four kinds of node — **concepts, code symbols, doc sections, rules** — joined by typed, provenance-weighted edges (`implements`, `describes`, `constrains`, `relates`, `calls`), each carrying a confidence and a `proposed / confirmed / rejected` status.

---

## Quickstart

Needs **JDK 17+** (the Gradle wrapper is included). Run **from the repo you want to understand**:

```bash
# one-time build
./gradlew :cli:installDist
stele=$PWD/cli/build/install/stele/bin/stele     # (Windows: ...\stele.bat)

cd /path/to/your/repo
$stele init                            # creates ./.stele/graph.db
$stele ingest symbols .                # code → candidate concepts (tree-sitter, 15 languages)
$stele build-ontology --model llama3.1 # LLM names/defines concepts (LOCAL Ollama; or --provider anthropic)
$stele dedupe-concepts                 # merge folder-twins
$stele ingest docs .                   # product docs → rules, relations, product language
$stele review --accept-above 0.8       # confirm the strong links (or run interactive)

# ask it things
$stele concept Auth                    # a concept: definition + rules + related + docs + code
$stele explain path/to/file.go         # what a file is part of, and the product rules it must respect
$stele mcp                             # serve the whole graph to an agent over stdio MCP
```

Everything is **local and offline** — the LLM step defaults to a local [Ollama](https://ollama.com) model; cloud (Anthropic) is opt-in (`--provider anthropic`, needs `ANTHROPIC_API_KEY`).

---

## What your agent gets

Register the server once (Claude Code): `claude mcp add stele -- /abs/path/stele mcp` (run from the indexed repo). The agent now has three tools:

**`concept_context("Auth")`** — resolves by name *or product alias* and returns the whole knowledge node:

```
concept: Authentication  [IAM]
Verifies identity and issues, validates and refreshes access/refresh tokens and sessions.
aliases: Auth, AccessToken, Session, Live session
related concepts: User, Authorization, Organization, Secret …
product rules:
  ‣ RBAC is deny-by-default; a new action requires a matrix entry.
  ‣ The access token contains only identity claims.
described in product docs: authentication.md, db/SCHEMA.md#auth …
implemented by 116 symbols across 23 files:
  [go] apps/auth/…  apps/api/…  apps/s0c/…      [ts] apps/console/src/features/auth/…
```

One concept — three Go services **and** a TS frontend — definition, rules, docs, and code, all at once.

**`context_for_code(path)`** — *before* the agent edits a file, it learns the file's concept(s), their **product rules**, related concepts, docs, and symbols — so it codes within the rules instead of guessing.

**`why_code(path)`** — the reverse: which product capability this code belongs to.

(All three are also CLI commands: `concept`, `explain`, `why`.)

---

## Architecture

Kotlin / JVM, Gradle multi-module monorepo. One local SQLite graph (`sqlite-jdbc`), schema via **Liquibase**.

| Module | Role |
|---|---|
| `core` | the graph store (concepts / artifacts / edges), data model, DB + migrations |
| `extractors` | tree-sitter symbol & token extraction (`io.github.bonede`, 15 languages, natives in-jar) |
| `connectors` | resolved code-graph ingest (`ast-index` SQLite reader · GitNexus JSON) + Markdown **docs** ingest |
| `resolver` | LLM concept canonicalization — local **Ollama** (default) · **Anthropic** (opt-in) · offline replay |
| `mcp` | MCP stdio server: `concept_context` · `why_code` · `context_for_code` |
| `cli` | the `stele` command (`application` → `installDist`) |

The **code graph is a commodity** — Stele consumes it (its own tree-sitter pass, or a real `ast-index`/GitNexus graph). The **concept spine on top is the IP.**

---

## Status (honest)

A working prototype, not production. What's real today:

- ✅ **End-to-end works, fully local** — ingest → ontology → docs/rules → human review → MCP, offline (LLM via local Ollama).
- ✅ **Code layer is high-precision** — symbols→concepts (folder clustering on organized repos) and resolved `calls` from a real indexer.
- ✅ **One unified graph** — code, docs, and rules all hang off shared concepts; cross-language by construction.
- ⚠️ **Product layer is deterministic-first and noisy** — doc→concept / rule / relation matching is broad keyword & co-occurrence, so it produces many low-confidence *proposals*. The fixes — a stricter **serving gate** and the human **`review`** loop — exist but aren't fully tightened. Treat unconfirmed edges as suggestions.
- 🔜 **Not built yet** — Figma / design layer, git+PR evidence (*why this code exists*), vector recall (`sqlite-vec`), a tightened quality gate, broader tests.

---

## Roadmap

`code → concepts` ✅ → `product docs + rules` ✅ → `human curation` ✅ → `serve to agent (MCP)` ✅ → **quality gate** → **design (Figma)** → **evidence (git/PR)** → federation.

See `docs/` (`build-spec.md`, `architecture.md`, `linking-design.md`, `phase-1-spec.md`) and `CLAUDE.md` for the full design and current state.

## Tech

Kotlin/JVM · Gradle · SQLite (`sqlite-jdbc`; `sqlite-vec` later) · Liquibase · java-tree-sitter · clikt · local **Ollama** / **Anthropic** for the LLM step · hand-rolled MCP (stdio JSON-RPC). Local-first and zero-infra by design — the whole graph is a single file under `.stele/`.

## License

MIT
