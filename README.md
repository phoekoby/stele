# Stele

**The product↔code Rosetta stone — give your AI coding agent a developer's full context.**

A *stele* is a stone slab carrying the same text in several scripts (the Rosetta Stone is one). Stele does that for software: **one navigable graph** that carries the meaning of your **code** and your **product docs** — and later your **design** — joined by a shared **concept layer** (your team's ubiquitous language). It runs locally (one SQLite file, zero infra) and serves that graph to AI agents over **MCP**.

---

## Measured results (before any claims)

Two independent evaluations on real repositories test the core thesis. Full protocol, golden
sets, per-run numbers and the iteration history live in [`eval/`](eval/README.md).

**1. Support Q&A with a small model** — 30 real user questions taken from
[Documenso](https://github.com/documenso/documenso)'s GitHub Discussions; the answering model
is held constant (**llama3.2:3b**), only the retrieval changes; judge llama3.1:8b under a
strict rubric, **K=3 samples per question** (± is a 95% CI); the same embedding model
(nomic, task-prefixed) for every arm that embeds:

| retrieval arm | concept-hit | artifact-recall | ~tokens | answer score (K=3) |
|---|---|---|---|---|
| **Stele — semantic resolve + drill** | **86.7%** | **87.5%** | 2395 | 3.41 ±0.20 |
| Stele — lexical resolve (baseline self) | 53.3% | 62.5% | 1950 | 3.08 ±0.25 |
| naive vector RAG (chunk + embed + top-k) | —¹ | 29.2% | 2412 | 3.54 ±0.17 |
| agentic grep (same 3B drives a GREP/READ loop) | —¹ | 0% | **6601** | **2.80 ±0.18** |

The verdict, per axis:
- **Retrieval: settled, 3× the artifact recall** of vector RAG at equal tokens — the answer
  can *cite where it came from* 87% of the time vs 29%. Vector RAG's failure here is
  structural, not embedding quality: strengthening its embedder (proper nomic task
  prefixes) did not move it — a 50-line chunk is the wrong retrieval unit for a concept
  that spans docs + code + rules.
- **Answers: statistical parity with vector RAG** (paired 11W/14L/4T, sign p=0.69) — said
  plainly: on single-doc "how does X work" questions, raw chunks answer as well as the
  graph. Parity was reached only after a measured lesson: the graph found better material
  but *served it too thin* (700-char bodies lost to raw chunks, p=0.035); matching the
  content density closed the gap.
- **Agentic search on a small model: significantly worst and 2.7× the cost** — 2.80 ±0.18,
  p=0.015 vs the graph, p=0.002 vs vector, 6.6k tokens/question. A 3B model cannot afford
  to explore; curated context is how a small model competes.

**2. Rule-compliance of a coding agent** — 41 trap tasks on 2 repos (each task seeded from a
real product rule), deterministic regex/static checkers, no LLM judge:

```
plain 24%   =   ast-index (code structure) 24%   <   Stele (concepts + rules) 39%
McNemar ast-index → Stele: b=6, c=0, p = 0.031 (significant)
```

Code structure carries no product rules — the concept layer does. The two axes are
independent measurements of the same claim.

**Exp1 caveat (negative result, published):** the *delivery mechanic* is not the moat — on
Documenso's full corpus (159 rules) `cat all_rules.md` in the prompt ties the scoped rule
(92% = 92% compliance on a modern small model; flat dilution curve). What prompt-stuffing
cannot do is *produce and maintain* that corpus (rule mining, concept-anchoring, staleness,
drift audit) — see [`eval/exp1-rule-dilution.md`](eval/exp1-rule-dilution.md).

**Why it works**
- **The retrieval unit matches the question unit.** Questions are about *concepts*; a concept
  spans docs + code + rules. The ontology resolves the concept first (50-way, 90% hit).
- **Hybrid drill: the graph picks the neighbourhood, the vector picks the house.** Sections
  *inside* the resolved concept are ranked by similarity to the question (+8pp recall,
  +0.13 answer score from this step alone).
- **Context carries content, not pointers** — section bodies, product rules and a compact
  code map; things a small model can actually answer from.

**Honesty box.** Every eval iteration fixed a methodology bug *in the baseline's favour*
(un-strawmanned the embedder; kept and published the runs where we *lost* — pointer-only
context, then under-dense context; a judge rubric that stopped rewarding refusals; agent
instructions that had been silently ingested as product docs are now a typed AGENT layer).
We do not claim answer superiority over vector RAG — the measured claim is *parity at 3×
the citation-grounding, and a significant win over agentic search at a third of its cost*.
Where the graph should pull ahead — cross-artifact questions, rule/mismatch checks,
multi-repo routing — is exactly the rule-compliance axis below plus planned next stages.
Remaining limits: one repo, single question-draw authored by us (a second independent
repo/stack is needed before claiming generalization); 29 judged questions; an 8B judge;
K=3. The measured numbers use the **nomic** embedder — the out-of-the-box default is the
offline lexical **floor** (`hashing`), which runs below these numbers (the CLI warns);
set `embedProvider: ollama` for the measured quality. Ontology quality is model-gated: a
strong (cloud) LLM for the one-shot `build-ontology`/`drift` steps, local for the rest.

¹ neither vector RAG nor agentic grep names concepts — compare them on recall/score/cost.

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
$stele init                            # creates ./.stele/graph.db AND scaffolds stele.yml
# edit stele.yml — point at your code, docs, and any wiki/Jira URLs — then:
$stele sync                            # runs the WHOLE pipeline from config (sources → ontology → docs → rules → dedupe → review)

# ask it things
$stele concept Auth                    # a concept: definition + rules + related + docs + code
$stele explain path/to/file.go         # what a file is part of, and the product rules it must respect
$stele graph                           # export an interactive HTML map of the whole graph — open it to eyeball quality
$stele mcp                             # serve the whole graph to an agent over stdio MCP
```

**`stele.yml`** is where you configure everything — sources, external doc URLs, the LLM, the review threshold:

```yaml
llm:     { provider: ollama, model: llama3.1 }     # local & offline by default; anthropic is opt-in
sources:
  - { type: symbols, path: "." }                   # code → concepts (tree-sitter, incremental)
  - { type: docs,    path: "." }                   # Markdown → rules, relations
  - { type: web,     urls: ["https://your-wiki/spec", "https://jira/browse/PROJ-1"] }
review:  { acceptAbove: 0.8 }
```

Adding a new connector (Notion, Slack, an API…) is a small class implementing `Connector` plus one line in the registry. Prefer the manual steps? Each pipeline stage is still its own command (`ingest symbols`, `build-ontology`, `dedupe-concepts`, `ingest docs`, `review`), and `stele search <term>` finds concepts by name/alias/definition from the terminal.

The LLM step is **local and offline by default** (a local [Ollama](https://ollama.com) model). Cloud is opt-in and provider-pluggable — **Anthropic**, **DeepSeek**, **OpenAI**, or any OpenAI-compatible endpoint:

```yaml
llm: { provider: deepseek, model: deepseek-chat }   # set DEEPSEEK_API_KEY in your env
# any other compatible API: provider + model + baseUrl + apiKeyEnv
```

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
- ✅ **You can see it** — `stele graph` exports a single offline HTML map (force-directed, searchable, click a concept for its definition + rules + code) so you can eyeball the index quality, not just trust it.
- ✅ **Stays fresh, re-indexes incrementally** — each file's mtime is tracked, so `ingest symbols` re-parses only what changed (a no-op re-run touches nothing); `stele install-hook` keeps it current on every commit, and `context_for_code` warns the agent when the file it's editing changed since indexing.
- ⚠️ **Product layer is deterministic-first and noisy** — doc→concept / rule / relation matching is broad keyword & co-occurrence, so it produces many low-confidence *proposals*. The fixes — a stricter **serving gate** and the human **`review`** loop — exist but aren't fully tightened. Treat unconfirmed edges as suggestions.
- 🔜 **Not built yet** — Figma / design layer, git+PR evidence (*why this code exists*), vector recall (`sqlite-vec`), a tightened quality gate, broader tests.

---

## Benchmarks — measured (honest)

Run on three public repos — **PocketBase** (Go + JS), **CloudNativePG** (Go), **Coder** (Go + TS) — indexed end-to-end with a **local** `gpt-oss:20b` for `build-ontology`. Reproducible from the CLI; method notes inline.

**Coverage & precision**

| | indexed | concept→code links verified | docs linked |
|---|---|---|---|
| 3 repos combined | **40,436** code symbols | **714 / 714 = 100%** (0 dangling) | **675** doc pages |

Every `implements` edge from a canonicalized concept was checked against source — the file exists and the symbol is a real declaration. **Zero hallucinated links.**

Canonicalization is **high-precision, low-recall** on a small local model: PocketBase **207 candidates → 26 clean concepts** (`Collection`, `Backup`, `MFA`, `OTP`…), CloudNativePG **152 → 11** (`WalArchive`, `PgBouncer`, `Publication`…). The rest stay `unresolved` for `review` or a stronger model; the **kept** set is what's verified above.

**A/B — agent codebase-Q&A, with vs without Stele** (PocketBase, subagents, blind judge vs gold answers)

| Mode | Answer quality | Context per question |
|---|---|---|
| grep/read agent (baseline) | ~97 / 100 | ~8,800 tokens read |
| **Stele map only** | ~84 / 100 | **~160 tokens** (≈50× less) |
| **Stele map + read** | **100 / 100** \* | full quality, straight to 2–3 files |

<sub>\* map+read n=2 — harness reliability was poor (several subagents failed); directional, not definitive.</sub>

**What the A/B proves**

- 🗜️ **~50× less context** — a concept map (~160 tok) answers what costs ~8,800 tok of file-reading, at ~85% of full-read quality. Cheap grounding for an agent.
- 🎯 **Straight to the target** — given the map, the agent reaches full-quality answers reading 2–3 files instead of searching the repo. The win is fewer steps and no missed files.
- 🔗 **Trustworthy navigation** — 100% of concept→code links point at real code, so the agent navigates instead of chasing ghosts.

**What it does *not* show (honest):** Stele doesn't make the agent *smarter* — a capable grep/read agent already scores ~97. The value is **cheaper context and precise navigation**, not higher intelligence. And map-only loses completeness on multi-file features (low recall) — there you want map-**plus**-read.

---

## Roadmap

`code → concepts` ✅ → `product docs + rules` ✅ → `human curation` ✅ → `serve to agent (MCP)` ✅ → **quality gate** → **design (Figma)** → **evidence (git/PR)** → federation.

See `docs/` (`build-spec.md`, `architecture.md`, `linking-design.md`, `phase-1-spec.md`) and `CLAUDE.md` for the full design and current state.

## Tech

Kotlin/JVM · Gradle · SQLite (`sqlite-jdbc`; `sqlite-vec` later) · Liquibase · java-tree-sitter · clikt · local **Ollama** / **Anthropic** for the LLM step · hand-rolled MCP (stdio JSON-RPC). Local-first and zero-infra by design — the whole graph is a single file under `.stele/`.

## License

MIT
