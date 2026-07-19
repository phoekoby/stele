import json, re, sys
from collections import defaultdict

I = re.IGNORECASE
def has(p, c): return bool(re.search(p, c, I))

CHECKS = {
 "T1_delete_completed":     lambda c: has(r'complet', c) and has(r'throw|error|cannot|soft|deletedat', c),
 "T2_owner_visibility":     lambda c: has(r'owner', c),
 "T3_send_needs_field":     lambda c: has(r'field', c) and has(r'signatur|sign', c) and has(r'every|some|length|each|throw|filter|>=|===\s*0|!\s*=', c),
 "T4_expiration_after_sent":lambda c: has(r'sent|status|draft|pending', c) and has(r'throw|error|cannot', c),
 "T5_recipient_after_sent": lambda c: has(r'sent|status|draft|complet|already|interact', c) and has(r'throw|error|cannot', c),
 "T6_recipient_expiry_pending": lambda c: not bool(re.search(r"(document|envelope)[\s\S]{0,80}status[\s\S]{0,25}expired|status\s*:\s*['\"]expired", c, I)),
 "T7_token_team_scope":     lambda c: has(r'teamid', c),
 "T8_org_transfer_admin":   lambda c: has(r'admin', c) and has(r'throw|error|cannot|instance|forbidden|unauthor', c),
}
ARM_ORDER = ["cat159s","cat300","cat600","cat1200"]

def load_results(path):
    raw = open(path).read()
    obj = json.loads(raw)
    # unwrap {"result": ...} and possible double-encoding
    for _ in range(3):
        if isinstance(obj, dict) and 'result' in obj: obj = obj['result']
        if isinstance(obj, str):
            try: obj = json.loads(obj)
            except: break
    return obj

res = load_results(sys.argv[1])
# res: list of {id, task, arm, code}
cell = {}  # (task,arm) -> [pass bools]
by_task_arm = defaultdict(list)
for r in res:
    t, a, code = r["task"], r["arm"], r.get("code","") or ""
    ok = CHECKS[t](code)
    by_task_arm[(t,a)].append(ok)

tasks = sorted(CHECKS.keys())
print(f"{'task':28}" + "".join(f"{a:>9}" for a in ARM_ORDER) + "   discriminating?")
print("-"*95)
arm_tot = defaultdict(lambda:[0,0])
disc = []
for t in tasks:
    row=""
    is_disc = t in ("T1_delete_completed","T4_expiration_after_sent","T5_recipient_after_sent","T6_recipient_expiry_pending")
    if is_disc: disc.append(t)
    for a in ARM_ORDER:
        v = by_task_arm[(t,a)]; p = sum(v); n = len(v)
        arm_tot[a][0]+=p; arm_tot[a][1]+=n
        row += f"{str(p)+'/'+str(n):>9}"
    print(f"{t:28}{row}   {'YES' if is_disc else '-'}")
print("-"*95)
tot=""
for a in ARM_ORDER:
    p,n = arm_tot[a]; tot += f"{str(round(100*p/max(1,n)))+'%':>9}"
print(f"{'ALL tasks pass-rate':28}{tot}")

# curve on discriminating tasks only (where the rule actually matters)
print(f"\nDISCRIMINATING tasks only ({len(disc)}): {', '.join(disc)}")
if disc:
    print(f"{'':28}" + "".join(f"{a:>9}" for a in ARM_ORDER))
    dt=""
    for a in ARM_ORDER:
        p=sum(sum(by_task_arm[(t,a)]) for t in disc); n=sum(len(by_task_arm[(t,a)]) for t in disc)
        dt += f"{str(round(100*p/max(1,n)))+'%':>9}"
    print(f"{'compliance':28}{dt}")
    print("\nCURVE (compliance vs #rules in context, discriminating tasks):")
    print("  plain(0 rules) -> stele(1 scoped) -> cat10 -> cat40 -> cat159(all)")
