# Experimental protocol — does Stele's context improve agent rule-compliance?

Full, reproducible protocol for the three eval runs in this folder. Read together with
`check.py` (Sb0rka 2-arm), `check_documenso.py` (Documenso 3-arm), `dataset.jsonl`, and the
workflow script (`…/workflows/scripts/stele-3arm-eval-*.js`).

---

## 1. Hypothesis

Injecting the **product-rule context Stele extracts** into a coding agent's prompt raises the rate
at which its output respects team-specific product rules, **more than** (a) no context, and (b) a
**code-structure context** (the information a code-graph tool like ast-index serves: symbols, files,
data model — no rules).

Operational prediction: on *trap* tasks (a naive implementation silently violates a non-obvious rule),
`Stele > plain` and `Stele > ast-index`, while `ast-index ≈ plain` (structure carries no rules).

## 2. Environment (pin these to reproduce)

| Component | Value |
|---|---|
| Stele commit | `070c951` (this repo) |
| JDK | 21 (Corretto), Windows 11 |
| Agent-under-test model | Claude Sonnet (`sonnet`), one sample per cell |
| Judge | **none** — deterministic static checkers (see §7). Cross-checked once vs a blind LLM judge. |
| Documenso | `github.com/documenso/documenso` @ `c219305eb1b60a9df4232b602434045318246344` (2026-06-27) |
| Bank of Anthos | `github.com/GoogleCloudPlatform/bank-of-anthos` @ `b739de1660afc587461558f9fe8c55d1ad1696a6` |
| Sb0rka | private repo (local), cleaned-rule graph |

**Determinism caveat:** the agent and the workflow's task-author are LLMs (stochastic). Exact pass/fail
cells will vary run to run. For a stable number, pin the model and run **K≥3 samples per cell**, report
mean ± CI. The numbers here are single-sample, and are labelled as pilots.

## 3. Build the Stele context for a repo

```bash
./gradlew :cli:installDist                     # build Stele
stele=…/cli/build/install/stele/bin/stele
git clone --depth 1 <repo> R && cd R
$stele init
$stele ingest symbols .                        # tree-sitter declarations -> concepts (no LLM)
$stele ingest docs .                            # Markdown -> describes + RULES (deterministic, no LLM)
# build-ontology (LLM) is OPTIONAL here: rules come from `ingest docs` deterministically.
```

Rule yield is the gate for whether a repo is usable:

| Repo | symbols | rules extracted | verdict |
|---|---|---|---|
| Documenso | 2899 | **177** | rule-rich — usable |
| Sb0rka | 971 | **34** (after cleanup) | rule-rich — usable |
| Bank of Anthos | 356 | **2** (both *deployment*) | **rejected** — Google demos document deploy, not business rules |

## 4. Extract the task seeds (rules)

```sql
SELECT a.title, MIN(cc.name) FROM artifacts a
  JOIN edges e ON e.src_id=a.id JOIN concepts cc ON cc.id=e.dst_id
  WHERE a.kind='rule' AND e.type='constrains' GROUP BY a.title;
```
Filter to genuine business rules: length 20–160, contains a modal (`must|cannot|only|never|required|
read-only|immutable…`), drop code/JSON/JSX/URL/schema fragments. Then a manual pass dropped obvious
non-rules (UI strings, license text). Result: **45 seeds** (25 Documenso + 20 Sb0rka), embedded verbatim
in the workflow script's `SEEDS`.

## 5. Task design

Two task-construction modes were used.

### 5a. Hand-authored (runs 1 & 2)
- **Run 1 — Sb0rka, 2 arms, 10 tasks** (`check.py`, `tasks.md`): T1–T10. Each = prompt + Go stub + rule
  + a static check. 8 traps (T1–T4, T6–T9) + 2 controls (T5 email, T10 pagination).
- **Run 2 — Documenso, 3 arms, 5 tasks** (`check_documenso.py`): D1, D2, D3, D5, D6 — see §9 for each
  task's prompt, rule, and check. Contexts (the Stele rule, the ast-index structure) were pulled
  **authentically** from the Documenso graph (`stele concept <X>`).

### 5b. Auto-generated (run 3 — the workflow, ~45 seeds)
One **author agent** per seed turns a rule into a task. Exact prompt:

> You are designing ONE trap task for a code-context eval in the `<repo>` project (`<lang>`).
> SEED RULE: "`<rule>`" [concept: `<concept>`]. Decide if it's an ACTIONABLE business rule (else
> usable=false). If usable, produce: **prompt** (must NOT hint the rule), **stub** (short `<lang>` code +
> TODO), **astindexCtx** (2-3 lines of code structure, no rule), **steleCtx** ("concept: X | rule: …"),
> **violationRegex** (matches VIOLATING code → FAIL), **fixRegex** (correct code MUST match → PASS).

forced to a JSON schema `{usable, prompt, stub, astindexCtx, steleCtx, violationRegex, fixRegex}`.
Seeds where `usable=false` or with no usable regex are dropped.

## 6. The three arms

Identical model, identical task & stub; the **only** difference is the appended context block:

| Arm | Appended context |
|---|---|
| `plain` | — (nothing) |
| `ast-index` | `CODE STRUCTURE (symbols & models, no product rules): <files/symbols + data model>` |
| `stele` | `PRODUCT CONTEXT (Stele): concept: <X> | rule: <the product rule>` |

**ast-index is a disclosed stand-in:** the binary is Rust+C and was not installed here. Its arm carries
exactly the information ast-index serves — symbols, files, data model — and provably **not** the rule.
That is the honest, fair comparison: *code structure* vs *rules* vs *nothing*.

Candidate run prompt:
> Senior `<lang>` engineer on the `<repo>` project. Complete the TASK. Output ONLY the final `<lang>`
> code, no prose. Do NOT use tools. TASK: … CODE: `<stub>` `<context>`

## 7. Scoring — deterministic, no LLM judge

Each task carries a static check. A candidate's code string is scored in-process:

```js
passed = (violationRegex ? !violationRegex.test(code) : true)
       && (fixRegex      ?  fixRegex.test(code)      : true)
```
For the hand-authored runs the checks are explicit predicates (e.g. T8: FAIL iff
`/\.System\s*=\s*in\.System/`; D2: FAIL iff `/EXPIRED/`). This is the SWE-bench-style "hidden test"
approach — reproducible and judge-free. (Validated once: on Documenso D1–D6 the deterministic checks
agreed with an independent **blind** LLM judge, A/B order alternated.)

## 8. Aggregation & statistics

- **Pass-rate per arm** = passes / scorable cells (overall and per repo).
- **McNemar exact test** on paired binary outcomes, per arm-vs-Stele:
  count discordant pairs `b` (baseline FAIL, Stele PASS) and `c` (baseline PASS, Stele FAIL);
  exact two-sided `p = 2·Σ_{k=0..min(b,c)} C(b+c,k)·0.5^(b+c)`.
  For an all-positive effect (`c=0`), `p<0.05` needs **b ≥ 6** discordant pairs.

## 9. The exact tasks

**Run 2 — Documenso (3-arm), the five tasks:**

| id | prompt (what the dev is asked) | product rule (Stele arm) | check |
|---|---|---|---|
| D1 | implement `deleteDocument(id)` | completed documents cannot be deleted | PASS iff guards on `COMPLETED` |
| D2 | on recipient expiry, update the document status | document **stays PENDING** after recipient expiry | FAIL iff sets status `EXPIRED` |
| D3 | after a recipient signs, notify the next | advance only when **all at current level** completed | PASS iff checks `.every(... COMPLETED)` |
| D5 | implement `canViewDocument` for `visibility==='ADMIN'` | the **owner** can still view an Admins-only doc | PASS iff owner exception (`ownerId`) |
| D6 | return the documents accessible via an API token | a token **cannot reach other teams** | PASS iff scoped by `teamId` |

**Run 1 — Sb0rka (2-arm), the ten tasks** (full prompts/stubs in `check.py` / `tasks.md`):
T1 perms-in-access-token · T2 refresh-token-in-body · T3 create-system-tag · T4 delete-system-secret ·
T5 validate-email *(control)* · T6 log-access-token · T7 secret-value-in-list · T8 update-system-tag ·
T9 store-password · T10 pagination *(control)*.

**Run 3 — the 45 seed rules** are listed verbatim in the workflow script `SEEDS` array; tasks are the
author agent's output for the usable ones (retrievable from the workflow result `rows` + agent transcripts).

## 10. Results

| Run | repo | arms | N | result |
|---|---|---|---|---|
| 1 | Sb0rka | plain, Stele | 10 | plain **60%** → Stele **100%**; 4/10 discriminating; McNemar p=0.125 |
| 2 | Documenso | plain, ast-index, Stele | 5 | plain **40%**, ast-index **40%**, Stele **100%**; ast-index = plain (+0pp), Stele +60pp; p=0.25 |
| 3 | Documenso + Sb0rka | plain, ast-index, Stele | 41 (of 45) | plain **24%**, ast-index **24%**, Stele **39%**. McNemar ast-index→Stele b=6,c=0 **p=0.031 (sig)**; plain→Stele b=7,c=1 p=0.070. (`run3_result.json`) |

**Run 3 reading (the multi-repo scale-up).** ast-index = plain again (24%=24%) — code structure gives no
lift on rule-compliance, now over 41 tasks, and the gap to Stele is **significant** (p=0.031). Stele leads
everywhere (+15pp overall; +4–12pp Documenso, +18–29pp Sb0rka) but the effect is far smaller than the
hand-curated pilots' +60pp: auto-generated regex checkers are stricter/noisier (many tasks both-fail,
depressing absolute rates) and a broad rule mix includes rules the model already obeys. **The robust signal
is the delta and its direction (ast-index ≤ plain < Stele), not the absolute pass-rate.**

## 11. Reproduce, end to end

1. Pin & clone the repos (§2 commits). Build Stele @ `070c951`.
2. `ingest symbols` + `ingest docs` each repo (§3); confirm rule yield (§3 table).
3. Extract & filter seeds (§4) — or reuse the 45 in the workflow script.
4. **Hand-authored runs:** `python eval/check.py` and `python eval/check_documenso.py` re-score the
   recorded candidate outputs. To regenerate candidates, replay the prompts in §5a/§6 against your agent.
5. **Workflow run:** `Workflow({scriptPath: …/stele-3arm-eval-*.js})` (or re-author from `SEEDS`).
6. For statistical power, set **K≥3 samples/cell**, pin the model, aggregate with McNemar (§8).

## 12. Threats to validity (read before citing a number)

- **Stand-in arm.** ast-index not run; its arm is an information-equivalent code-structure context. A
  real ast-index integration could format/scope context differently.
- **Controlled injection, not live MCP.** The context is injected into the prompt; the real agent must
  *decide to call* the MCP tool — adds tool-use noise we removed to isolate the context's value.
- **Agent-under-test is a subagent (Sonnet), not Claude Code + MCP.** The isolated variable is the context.
- **Auto-generated checks (run 3) are heuristic regexes** authored by an LLM; some will mis-score. The
  hand-authored checks (runs 1–2) are human-written.
- **Selection.** Tasks are rule-sensitive — Stele's lane. Controls (Sb0rka T5/T10) included to show no-harm.
  Some "traps" are common-sense (model already complies → all arms pass), which dilutes toward the truth.
- **Single sample, small N.** Not yet statistically significant (runs 1–2). Run 3 + K-sampling address this.
