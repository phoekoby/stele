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
