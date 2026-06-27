"""Stele 3-arm eval on Documenso (real OSS, e-signature, TypeScript).

Arms: plain (no context) | ast-index (code structure: symbols+models, NO rules) |
Stele (concept + product rules). Deterministic static checks, no LLM judge.
Contexts are authentic, pulled from the Documenso graph Stele ingested.

ast-index isn't installed here (Rust+C); its arm is a faithful code-structure
stand-in — exactly the information a code graph serves (symbols, files, models),
without product rules. That is the honest comparison: structure vs rules.
"""
import re

ARMS = ("plain", "ast-index", "stele")

# produced code per task per arm (decision-relevant excerpts, verbatim)
RUNS = {
 "D1_delete_completed": {
  "plain":     "if (document.status === 'PENDING' || document.status === 'COMPLETED') { throw new Error('Cannot delete'); } return prisma.document.delete({ where: { id } });",
  "ast-index": "if (document.status === DocumentStatus.COMPLETED) { throw new Error('Cannot delete a completed document'); } return prisma.document.delete({ where: { id } });",
  "stele":     "if (document.status === 'COMPLETED') { throw new Error('Completed documents cannot be deleted.'); } return prisma.document.delete({ where: { id } });",
 },
 "D2_expire_stays_pending": {
  "plain":     "if (allExpiredOrSigned) { await prisma.document.update({ where: { id: doc.id }, data: { status: 'EXPIRED' } }); }",
  "ast-index": "const newStatus = allExpiredOrCompleted ? 'EXPIRED' : 'PENDING'; await prisma.document.update({ where: { id: recipient.documentId }, data: { status: newStatus } });",
  "stele":     "const recipient = await prisma.recipient.update({ where: { id: recipientId }, data: { expired: true } }); return recipient;",
 },
 "D3_sequential_notify": {
  "plain":     "const nextOrder = recipients.filter((r) => r.signingOrder > currentOrder && r.status === 'PENDING').reduce(...); const nextRecipients = recipients.filter((r) => r.signingOrder === nextOrder); await Promise.all(nextRecipients.map(...send));",
  "ast-index": "const nextOrder = recipients.filter((r) => r.status === 'PENDING' && r.signingOrder > signed.signingOrder).sort(...)[0]?.signingOrder; const nextRecipients = recipients.filter((r) => r.signingOrder === nextOrder); for (...) await sendDocument(...);",
  "stele":     "const allAtCurrentLevel = recipients.filter((r) => r.signingOrder === currentOrder); const allCompleted = allAtCurrentLevel.every((r) => r.status === 'COMPLETED'); if (!allCompleted) return; const nextOrder = Math.min(...pendingOrders); ...",
 },
 "D5_owner_visibility": {
  "plain":     "if (doc.visibility === 'ADMIN') { return user.role === 'ADMIN'; } return true;",
  "ast-index": "if (doc.visibility === 'ADMIN') { return user.role === 'ADMIN'; } return true;",
  "stele":     "if (doc.visibility === 'ADMIN') return user.role === 'ADMIN' || user.id === doc.ownerId; return false;",
 },
 "D6_token_team_scope": {
  "plain":     "return prisma.document.findMany({ where: token.teamId ? { teamId: token.teamId } : { userId: token.userId, teamId: null } });",
  "ast-index": "return prisma.document.findMany({ where: token.teamId ? { teamId: token.teamId } : { userId: token.userId, teamId: null } });",
  "stele":     "return prisma.document.findMany({ where: token.teamId ? { teamId: token.teamId } : { userId: token.userId, teamId: null } });",
 },
}

CHECKS = {
 # rule: completed documents cannot be deleted -> must guard on COMPLETED
 "D1_delete_completed":   lambda c: "COMPLETED" in c,
 # rule: document stays PENDING after recipient expiration -> must NOT set it EXPIRED
 "D2_expire_stays_pending": lambda c: "EXPIRED" not in c,
 # rule: advance only when ALL at current level complete -> must check completion of the level
 "D3_sequential_notify":  lambda c: bool(re.search(r"\.every\(", c)),
 # rule: owner can view even if Admins-only -> owner exception present
 "D5_owner_visibility":   lambda c: "ownerId" in c,
 # rule: token scoped to its team -> must filter by teamId
 "D6_token_team_scope":   lambda c: "teamId" in c,
}

def main():
    score = {a: 0 for a in ARMS}
    n = len(RUNS)
    w = 22
    print(f"{'task':24} " + "".join(f"{a:>11}" for a in ARMS))
    print("-" * 60)
    for task, runs in RUNS.items():
        cells = []
        for a in ARMS:
            ok = CHECKS[task](runs[a])
            score[a] += ok
            cells.append("PASS" if ok else "FAIL")
        print(f"{task:24} " + "".join(f"{c:>11}" for c in cells))
    print("-" * 60)
    print(f"{'TOTAL pass-rate':24} " + "".join(f"{str(100*score[a]//n)+'%':>11}" for a in ARMS))
    print()
    base = score["plain"]
    print(f"plain {100*score['plain']//n}%  |  ast-index {100*score['ast-index']//n}%  |  Stele {100*score['stele']//n}%")
    print(f"ast-index over plain: +{100*(score['ast-index']-base)//n}pp   Stele over plain: +{100*(score['stele']-base)//n}pp")

if __name__ == "__main__":
    main()
