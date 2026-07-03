# Stele agent-quality eval (v1)

Does giving a coding agent Stele's concept context make it write **product-rule-compliant** code?
Paired A/B: the **same** model and prompt; the only difference is whether the block Stele's MCP would
return (`concept_context` / `context_for_code`) is injected. Scoring is a **deterministic static check**
per task — no LLM judge in the loop (like SWE-bench hidden tests), so the number is reproducible.

## Files
- `dataset.jsonl` — the task suite: id, concept, kind (trap/control), prompt, the product rule, the check.
- `check.py` — the candidate outputs from one paired run **plus the deterministic checker**. `python eval/check.py`.
- `tasks.md` — full prompts, stubs, and the Stele context block injected in the `with` arm.

## Method
1. **Tasks** tied to real product rules Stele extracted from Sb0rka's docs. *Traps*: the naive
   implementation silently violates a non-obvious, team-specific rule. *Controls*: no hidden rule (no-harm check).
2. **Two arms** — `without` (task + stub) and `with` (+ the Stele context block). The agent under test is an
   isolated subagent told to output only code, no tools, no repo access — so the only variable is the context.
3. **Deterministic check** — a static assertion over the produced code returns PASS/FAIL. No model judges.
4. **Metric** — paired pass-rate `with` vs `without`, plus McNemar on the paired binary outcomes.

## Results (10 tasks, 1 run)

```
task                          without   with
T1 perms in access token         FAIL   PASS   <- Stele helped
T2 refresh token in body         FAIL   PASS   <- Stele helped
T3 create system tag             FAIL   PASS   <- Stele helped
T4 delete system secret          PASS   PASS
T5 validate email (control)      PASS   PASS
T6 log access token              PASS   PASS
T7 secret value in list          PASS   PASS
T8 update system tag             FAIL   PASS   <- Stele helped
T9 store password                PASS   PASS
T10 pagination (control)         PASS   PASS
                              -----------------
without = 60%            with = 100%
discriminating: 4/10   McNemar fixed=4 broken=0  (exact p=0.125)
```

## What this honestly shows
- **Stele moved 4/10 tasks, all in the right direction, 0 regressions.** Those four are exactly the
  **non-obvious, team-specific** rules (don't put permissions in the access token; refresh only via HttpOnly
  cookie; system tags can't be created or mutated by users). That is where domain context pays off.
- **On common-sense security the model is already safe without Stele** (T4 don't delete system secrets,
  T6 don't log tokens, T7 don't dump secret values, T9 hash passwords). Stele adds nothing there — and
  breaks nothing. Controls (T5, T10) unaffected.
- **Not yet statistically significant**: 4-vs-0 is directionally strong but N=10 gives exact McNemar
  p=0.125. A credible benchmark needs ~30–50 trap tasks (and ≥3 samples/arm for variance).

## 3-arm result — Documenso (real external OSS, TypeScript)

Stele ingested the real [Documenso](https://github.com/documenso/documenso) repo (e-signature):
2899 symbols / 190 docs / **177 rules** — a genuinely rule-rich repo (vs Google's Bank of Anthos,
which yielded 2 *deployment* rules: demos document how to deploy, not business rules). Five trap tasks
were curated from its real business rules; three arms, deterministic checks (`check_documenso.py`):

```
task                       plain   ast-index   Stele
D1 delete completed doc     PASS      PASS      PASS
D2 expire -> stays PENDING  FAIL      FAIL      PASS
D3 sequential signing       FAIL      FAIL      PASS
D5 owner visibility         FAIL      FAIL      PASS
D6 token team scope         PASS      PASS      PASS
                           ----------------------------
pass-rate                    40%       40%      100%
```

**ast-index = plain (+0pp); Stele +60pp.** This is the project thesis, measured on an external repo:
code structure (symbols / files / models) carries no business rules, so on rule-compliance a commodity
code graph is no better than nothing — while the concept+rule spine fixes all three discriminating tasks.
(ast-index isn't installed here; its arm is a faithful code-structure stand-in — exactly the symbols/models
it serves, which provably don't encode the rule. N=5, 3 discriminating → McNemar p=0.25, not yet significant.)

## Run 3 — multi-repo scale-up (45 seeds, Documenso + Sb0rka, a workflow)

Auto-generated tasks from 45 real product rules (an author agent turns each rule into a trap task +
a regex checker), 3 arms, 180 subagents. Deterministic scoring, no LLM judge.

```
arm         Documenso   Sb0rka   overall
plain          29%        18%     24%  (10/41)
ast-index      21%        29%     24%  (10/41)
Stele          33%        47%     39%  (16/41)
McNemar: ast-index -> Stele  b=6 c=0  p=0.031 (significant)
         plain     -> Stele  b=7 c=1  p=0.070 (borderline)
```

**ast-index = plain (24% = 24%) — confirmed at scale and now statistically separated from Stele.**
Stele leads (+15pp), significant vs ast-index. The effect is far smaller than the hand-curated +60pp
because auto-generated checkers are noisier (many tasks both-fail) and the rule mix is broader — so the
**robust claim is the ordering `ast-index ≤ plain < Stele`, not the absolute number.** (`run3_result.json`)

## Caveats
- **Controlled context-injection**, not the live MCP round-trip — isolates the value of the context itself.
  The real `claude -p` + MCP run adds tool-use noise (the agent must decide to call the tool).
- **Static checks are heuristic** — they encode each rule as a regex/AST assertion; they can be fooled by
  unusual phrasings. They cross-checked clean against an independent blind LLM-judge on T1–T5.

## Scale / next
Grow `dataset.jsonl` from confirmed `constrains` rules in the graph (each rule → a trap task + a static check),
run ≥3 samples per arm, and/or drive the real agent: register Stele as an MCP server and run
`claude -p "<task>"` on a clean Sb0rka checkout twice (MCP on / off), then run the same checks on the diffs.

## Retrieval benchmark (v2 axis) — support-Q&A on Documenso

The second eval axis (`stele eval`, `:eval` module): can the graph answer a **conversational
support question** better than naive vector RAG, with a small model? 30 real questions
(`golden.documenso.yml`) sourced from Documenso's GitHub Discussions + the graph's own rule
set. Graph: `ingest symbols` → offline canonicalization (197→50 concepts) → `ingest docs`.
Answerer **llama3.2:3b** (held constant), judge **llama3.1:8b** (strict rubric), embeddings
**nomic-embed-text** (with task prefixes) for every arm that embeds.

```
arm         concept-hit   artifact-recall   ~tokens   answer-score(3B)
stele          60.0%          54.2%          2322        3.41 / 5     (lexical resolve)
stele-sem      90.0%          85.4%          2219        3.68 / 5     (semantic resolve + question-aware drill)
vector          0.0%          29.2%          2412        3.45 / 5     (chunk+embed top-k)
```

Findings (each earned by fixing a real methodological bug — keep the order):
1. **Ontology-first retrieval: 3× the artifact recall of vector RAG at fewer tokens.**
   And vector's failure is *structural*, not embedding quality: hardening nomic with its
   task prefixes (un-strawmanning the baseline) left it at ~29% — the retrieval unit
   (50-line chunk) simply doesn't match the question unit (a concept spanning docs+code+rules).
2. **Pointers lose to content.** Serving section titles/file paths (high recall!) scored
   *below* raw chunks on answer quality — a one-shot model can't drill into a pointer.
   Serving section BODIES + a compact code map fixed it.
3. **Question-aware drill is the winning hybrid**: resolve the concept by ontology
   (50-way, 90% hit), then rank the concept's OWN sections by cosine to the question.
   Graph picks the neighbourhood, vector picks the house: recall 77→85%, score 3.55→3.68.
4. **Judge rubric matters**: under a strict rubric (refusal ≤ partial answer), vector's
   fluent-but-loose answers fell 3.91→3.45; stele-sem's grounded ones rose.
5. **Honest stats**: paired stele-sem vs vector 10 wins / 6 losses / 6 ties — sign test
   p≈0.45. Direction, not significance. A credible claim needs K≥3 samples/cell and a
   bigger judged set (same medicine as rule-compliance run 3).

Next: port semantic resolve + question-aware drill into MCP serving (`concept_context`),
`stele ask` (two-sided answer: docs-say vs code-does), agentic-grep arm, K≥3 sampling.

### K=3 update (Stage C) — the answer axis, decided

`--samples 3`, per-question mean scores, exact sign tests (all four arms, cleaned graph):

```
arm         concept-hit  artifact-recall  ~tokens  answer (K=3)
stele-sem      86.7%        87.5%          2395    3.41 ±0.20
stele          53.3%        62.5%          1950    3.08 ±0.25
vector           —          29.2%          2412    3.54 ±0.17
agentic          —           0%            6601    2.80 ±0.18

stele-sem vs vector   11W/14L/4T  p=0.69   (parity — after equalizing content density)
stele     vs vector    6W/17L/6T  p=0.035  (thin 700-char serving LOSES to raw chunks)
stele-sem vs agentic  19W/6L/4T   p=0.015  (graph beats agentic significantly)
vector    vs agentic  22W/5L/2T   p=0.002
```

Three lessons, in the order the data forced them:
1. **Serving density is a first-class variable.** The graph found better material (87.5%
   recall) but served 700-char snippets — and lost the answer axis to raw 2.4k chunks
   (p=0.035). Matching the token budget closed the gap to parity (p=0.69).
2. **Answer parity, not superiority, vs vector RAG** on single-doc how-questions — the
   honest claim. The graph's justified edge on this axis is *citation grounding* (the
   right artifacts are in context 87.5% vs 29.2%).
3. **Agentic grep on a 3B is the significantly worst option at 2.7× the cost** — the
   strongest evidence yet for "small models need curated context, not a search loop".

## Cross-repo (Stage D) — one graph over two real repos

Workspace layout: `documenso/` (the product) + `sdk-typescript/` (its official SDK) feed ONE
graph; refs are repo-prefixed (`documenso/packages/…`, `sdk-typescript/src/…`). 10 questions
(`golden.workspace.yml`) whose gold artifacts require BOTH repos — recall = cross-repo coverage.

```
arm         concept-hit  artifact-recall  ~tokens  latency
stele-sem      80%           75%           2122     0.3s
stele          70%           45%           2255     16ms
vector           —           50%*          2244     157s (26-min two-repo index on q1)
```
*vector's 50% is flattered: bare-repo gold prefixes match any chunk from that repo.

What the graph does here that chunks structurally can't: one question ("how do I create a
document from a template with the TypeScript SDK?") resolves to concepts and returns the
core repo's SDK guide, the SDK's README, and the SDK code map in one slice — because both
repos attach to the same concept spine.

Lesson (kept honestly): **alias errors compound.** One wrong seed alias in a canonicalization
verdict ("SDK" on the Embedding concept) attracted more wrong aliases at doc-ingest time
("Using the TypeScript SDK" → Embedding) and skewed resolution until cleaned. Concept-card
quality is a first-class input; alias provenance tracking is on the roadmap.
