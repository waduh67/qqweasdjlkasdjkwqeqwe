# Problems — warehouse-workorder-asset-provenance

Unresolved blockers and technical debt discovered during work on this plan.

_Auto-scaffolded by /start-work. Append new entries below - never overwrite._

---

## 2026-09-07T02:16:30Z - Task01 remaining dependency gates

- Task31 must supply the real browser config/spec, warehouse-e2e external-adapter isolation profile, and warehouse health identity contributor; task42 must supply materials module before those QA modes can succeed. These are explicit later-task blockers, not mock successes.
- LSP diagnostics cannot target this task worktree in the current tool session. Keep the limitation in task01 evidence; do not label diagnostics clean.

## 2026-09-07T03:38:35Z - Task02 remaining gates

- Task02's interfaces intentionally have no runtime bindings. Tasks03+ must supply real quantity validators, mandatory adapters, transaction/fence persistence, strict HTTP decoding/error mapping and runtime revocation/assignment tests. No new operational warehouse endpoint or bypass fix is claimed shipped by this contract-only task.
- Independent confirmation of task02 by the orchestrator remains required before task03. Main plan checkbox left untouched. All task evidence is worktree-local under task-2; main checkout changes are append-only notepad entries only.

## 2026-09-07T04:35:00Z - Task03 remaining ownership gates

- Canonical values do not resolve legacy collisions or enforce tenant-unique claims. Task04+ must preserve raw history, store receipt/SKU snapshots and enforce admission/claims/posted-unit immutability. Segment conservation here proves arithmetic, not atomic database lineage or concurrent stock posting.
- Pure value work does not implement warehouse services, schema, runtime deployment bindings, controllers or migrations. Task03 checkbox remains for orchestrator verification; detailed evidence resides in task-3 under the task worktree.

## 2026-09-07T05:40:00Z - Task04 downstream gates

- Tasks05/06 must route all legacy/new writers through posting, cutover and current-authority fences before enabling operational warehouse commands. Task04 deliberately does not add those services/endpoints; legacy unverified app inserts now fail closed at persistence boundaries.
- Task12 needs real approval validation and coordinated forward-only replacement of the closed cutover finalization guard; task43 owns interactive reconciliation/admission. Immutable outbox events are persisted here; consumer delivery/lease/retry state and behavior remain task06 ownership.

## 2026-09-07T07:10:04Z - Reverification required

- Original task04 DoneClaim was rejected by independent verifier session ses_f858660c5ffeUjv9n9Gy5kmvY8. Forward-fix evidence is additive under task-4/forward-fix; old evidence is preserved, not rewritten. Executor corrections pass but task04 must remain blocked until the same verifier confirms AV-01/02/03 and actual tenant-created integration.
- Task12 opening approval remains deliberately unavailable; task5/6 posting commands and task43 reconciliation remain outside this fix. Terminal serial snapshots may retain retired identity history, but cannot authorize positive positions, active segments or new encumbrance.

## 2026-09-07T08:44:49Z - Aggregate root reverification gate

- Prior blockers independently confirmed fixed; the aggregate lot fix is executor-verified but awaits the same verifier ses_f8526de97ffeAXdgJag71WChmx. Do not mark task04 complete based on the older DoneClaims.
- Future lot mutation callers must use the documented READ COMMITTED row-lock profile and retry serialization conflicts as whole transactions. Task5/6 commands and task12/task43 workflows remain out of scope.

## 2026-09-07T09:48:09Z - Deferred scope reverification gate

- Normal-context allocation was independently confirmed, but task04 remained blocked by the changed-GUC bypass. V174.4 is executor-verified and awaits verifier session ses_f8526de97ffeAXdgJag71WChmx; no plan checkbox changed.
- Deferred stock validation requires the row tenant scope to be present when checks execute. This applies to owner staging as well as application writes; restore unrelated GUC only after validation or use a tenant-bound transaction. READ COMMITTED mutation profile and future-task boundaries remain unchanged.

## 2026-09-07T11:12:49Z - Selective timing reverification gate

- V174.5 is executor-verified against the selective-timing defect. Request verifier ses_f8526de97ffeAXdgJag71WChmx resumption before marking task04 complete. Previous DoneClaims remain preserved as historical evidence, not current approval.
- Scope must be valid inside each actual deferred validator; executing only a companion is never sufficient. Task5/6 commands, task12 approval evidence and task43 reconciliation remain outside this correction.

## 2026-09-07T15:53:30Z - Task05 same-verifier gate

- Verifier ses_f83de1ba3ffesysl0fn4fQMZNY rejected original task05 claim with AV5-01 through AV5-05. Corrections are executor-verified and pushed at47398f83491a2861a5dfdf06055a157a3d20107a. Resume that same verifier against the new HEAD before marking task05 complete or unblocking task06.
- Task06 IAM/replay/outbox leases remain intentionally unimplemented. CodeGraph/LSP worktree limitation unchanged. Earlier DoneClaims/logs remain preserved as historical evidence; current correction receipt is task-5/forward-fix/DoneClaim.json.

## 2026-09-07T18:38:29Z - Remaining task05 verifier gate

- All earlier findings except picked increase and actual-line identity were independently confirmed at47398f83. Those remaining gaps and adjacent quantity/source cases are corrected and pushed at383e9dd8588a2b1353d0954d4a728e6adca60713. Resume verifier ses_f8358f7b9ffezDBYdyrjPqXgXG before marking task05 complete or unblocking task06.
- New additive evidence: task-5/forward-fix/actual-bindings/DoneClaim.json. No historical facts were rewritten; exhausted/inconsistent historical source allocations fail closed. Task06 and unavailable CodeGraph/LSP remain unchanged.

## 2026-09-07T20:12:31Z - Retained-fact verifier gate

- Verifier confirmed the preceding picked/identity corrections but found retained40m could produce a returned fact beside moved60m. Correction is executor-verified and pushed at0a63ac1d9ea74c6c723b4375b3daebc59056f2ec. Resume ses_f8358f7b9ffezDBYdyrjPqXgXG before marking task05 complete.
- Additive evidence: task-5/forward-fix/retained-facts/DoneClaim.json. Old erroneous history is not rewritten; no migration/task6 or production test-hook changes beyond removal-preserving checks.

## 2026-09-11 - Task15 boundaries for verification

- CodeGraph and LSP still cannot target this external worktree; compiler, schema, Spring and packaged HTTP are the validation channels, not a clean-LSP claim.
- Task15 discrepancy facts do not settle loss/rejection and discrepancy-only commands remain unavailable. Task16 must consume immutable receipt acceptance totals rather than frozen task14 document-line accepted_base. No use/return/settlement/customer assignment was added.
- Local notepad appends intentionally remain unstaged under the instruction forbidding .omo staging. Product delivery status must distinguish those local notes from product changes; independent task15 verification remains required before advancing the plan.
