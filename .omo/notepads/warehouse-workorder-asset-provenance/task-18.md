# Task18 Findings

## 2026-09-13 - Baseline and Red Lifecycle Evidence Only

- Task18 is NOT complete. Product source remains at checkpoint
  1e5194d1e0ea17ce09f6aa6ff5a8ceee5bd092fd; no migrations or commits were created.
- Isolated warehouse QA baseline passed75/0/0. Corrected lifecycle suite compiled
  and ran6 tests, all6 failing on missing task18 behavior. One earlier invalid
  reserved_base query was fixed before the authoritative red run.
- Source of truth: .omo/evidence/warehouse-workorder-asset-provenance/task-18/
  baseline.md, baseline-xml.tar.gz, red-xml.tar.gz, blocked.md and the preserved
  WorkOrderMaterialLifecycleIT.kt source artifact. The test is outside the source
  set to preserve the safe product checkpoint, not to claim a green task18 gate.
- Cancel leaves100000 unpicked units reserved; cancel after pick succeeds200 when
  task18 requires409. Post-use cancellation preserves82500 consumed/17500 held, but
  there is no settlement GET. Old actor use denial already works after reassignment;
  own-custody return remains403. Forced close uses the task13 placeholder409.
- Important exploration correction: WorkOrderPersistenceAdapter.findById takes
  findLockedById in write transactions (lines117-119). Do not implement a redundant
  lock fix based on the research agent's incorrect claim that the lock was absent.
- No external environment blocker occurred. Remaining blocker is unfinished
  lifecycle implementation and consequently unpassed acceptance gates. No full
  regression, task18 RLS/race/manual/restart/ultraqa or clean artifact claim exists.

## 2026-09-13 - Implementation Checkpoint Superseding the Earlier Blocker

- Implemented and pushed task18 owner APIs, cancellation preflight, immutable
  obligations/lifecycle revisions, explicit nonavailable return dispatch/ACK,
  three-party same-WO handover, positive use deltas, scoped private reads and
  independent material closure. Plan checkbox18 remains unchanged for review.
- Current local/remote HEAD: f7fe8211fb730c6a8e297f26a7833fb9a499f38e;21 commits,
  each immediately pushed. Gmail author unchanged. No task19 work.
- Migrations V175.37-.43 are forward-only and manifest-first. Never edit these now
  applied files or any predecessor; future corrections need a new subversion.
- Exact original6-case tests pass twice at release and retain their original
  source SHA2568880b50dbd9ec4f8ff09b432e693d79c80620596833d207af8333a5372a189a2.
-521 distinct tests pass including42 lifecycle cases. Latest complete suite per
  class is the deduplication unit: JUnit parameter display indices changed when
  adding the origin-obligation case, and raw name-based deduplication overcounts.
- Packaged JAR SHA25615d5a8438cd19e6ff9dcaff7733bea4a0651ef2bfec9f9631fc16f9ada2891cf.
  Live HTTP proved82500 consumed preserved,17500 explicit return transit then
  quarantine,900000 warehouse availability unchanged, replay after two SIGKILLs,
  scope-revoked denial, preissue release, picked409/unpick, partial handover7500,
  use only after named acknowledgement, sender10000 remainder and positive closure.
- Current report: .omo/evidence/warehouse-workorder-asset-provenance/task-18/DoneClaim.md
  and verified-results.json. Earlier blocked.md and baseline/red artifacts remain
  intentionally as historical evidence, not current blockers.
- Important guard corrections: shared PostgreSQL trigger NEW fields must route
  by actual table or JSON; final residual balances must reconcile immutable
  postings; cancellation racing a winning pick must fail409, not succeed200.
- Read/command extraction kept touched WorkOrderService below250 pure LOC at223.
  Query service66 and mapper30 preserve the WorkOrderIT behavior.
- No generic transfer, cross-WO reallocation, customer assignment, full return
  inspection/reissue, UI or mobile claim. Pending inspection remains an outstanding
  material closure obligation; task18 does not bypass task26.

## Final Acceptance Gap - Do Not Mark Task18 Complete

- Final source review found that MaterialPlanningStore.assertReplaceable still
  calls assertNoPhysicalFacts, rejecting new plans after an issued/used material
  history. REWORK/RESUBMIT snapshots do not contain explicit new plan/evidence
  revision links. Positive measured usage deltas do not fulfill that separate
  requirement. Complete this owner workflow and its failing-first tests next.
- This is unfinished implementation, not an infrastructure blocker. The pushed
  checkpoint and its521 passing tests remain valid for the implemented custody,
  cancellation, return/handover, delta and material-close behavior. No full task18
  acceptance or post-use replanning gate is claimed.
- Final documentation-only blocker checkpoint:1e9e5a0c91542498750e6688fa47413c44a8983c,
  22 total pushed commits since the starting safe checkpoint. Product artifact
  bytes are unchanged by this documentation commit.

## Post-Use Rework Gap Resolved - Recovery Checkpoint

- Supersedes the previous acceptance-gap note: dedicated `/materials/rework`
  now appends positive plan deltas with predecessor/inherited-line and old/current
  evidence links. GET `/materials/rework-context` supplies authoritative tokens.
  Generic `assertReplaceable/assertNoPhysicalFacts` remains unchanged and rejects
  ordinary used-plan replacement.
- New V175.44-.47 were applied and verified; never edit their bytes. All V175.37-.43
  and predecessors remain preserved. Direct reservation and operation inserts
  honor current evidence; release/unpick are not blocked by that positive-write fence.
- Current local/remote head58843e15b4317172d1b2e3d25f87cd015490bbcb:13 additive
  commits since1e9e5a0c, all immediately pushed using global Gmail identity.
- Recovery after provider request failure first confirmed the intact worktree,
  only manifest commit a7503c1f pushed, applied V175.44-.47 and saved passing gates.
  No product changes were discarded or applied migrations modified.
- Exact rework/lifecycle75 tests passed twice;111 fulfillment/WorkOrder/durability,
  159 usage/receipt,61 reservation/issue and151 schema/contracts/Modularity passed.
  Latest complete-suite deduplication yields554 distinct tests,33 new rework and
  42 existing lifecycle cases. All selected XML/exit gates have zero failures/skips.
- Current no-cache JAR SHA2560db31eed93860ff229cfed6365d54185fff57a9d5157bc5842e49fbe89771c50.
  Packaged QA preserves original82500; adds10000 requested/issued on plan2; records
  new5000 plus inherited7500 on plan2; totals95000 consumed/15000 accountable;
  restart replay and QA resubmission add no second debit. Existing packaged
  return/handover/nonavailability/closure also passes on the same artifact.
- Key corrections: deferred fresh-receipt use must not mistake its own new usage
  for prior usage; settlement coverage must span linked plans rather than only the
  last delta; direct owner routes require the same evidence fence as fulfillment.
- Current additive report and full file/commit/hash lists:
  `.omo/evidence/warehouse-workorder-asset-provenance/task-18/rework/DoneClaim.md`
  and `verified-results.json`. Preserve earlier evidence as historical checkpoints.
- No task19+ work or full return inspection/reissue. Task18 checkbox is unchanged
  pending independent review; this note does not mark it complete.

## Authoritative QA Projection Correction

- Verifier ses_f624a47dcffezn1hxq2R8wGyU5 found materials.qaState hardcodedPENDING
  while DB/detail wereAPPROVED. Red6 tests had3 behavioral failures (approved,
  rejected and cancelled-rejected); no-decision/pending controls already passed.
- Public WorkOrderMaterialContextApi.currentQaState now reads owner state under
  current-authority/WO locks. Fulfillment maps it into the inventory planning
  context; inventory summary consumes that value. No inventory->workorder
  repository dependency or callback/bean cycle was added.
- NULL maps conservatively to summaryPENDING; actualPENDING/APPROVED/REJECTED map
  directly. Cancelled state does not create approval or erase retained rejection.
  Frozen workorder DTO serialization/equality and historical receipts are unchanged.
- Legacy summary settlementStateOPEN is explicitly not a physical-close verdict;
  detail materialState is authoritative (SETTLING for pending inspection). Paired
  contract checks were added without changing stock or lifecycle behavior.
- Final mapping head58e509247bc46b9888d9dd731233aa16c8cb8d15, three immediately
  pushed commits from58843e15. Gmail author unchanged; product clean,0/0 remote.
-75 lifecycle/rework tests pass twice;111 task17/WorkOrder/Modularity and52
  usage/QA/contract tests pass.238 distinct final cases; no failures/errors/skips.
- Current no-cache JAR SHA2561a157b7da1502d12fec521681bcc9d9035c7481c621ea2bc0a01b2ee20aa004f.
  Packaged rework DB/materials/detail allAPPROVED before/after restart with
  110000/95000/15000/890000 preserved. Return DB/detailNULL and summaryPENDING
  remain consistent with100000/82500/17500/900000; history fingerprints unchanged.
- No migrations changed; V175.37-.47 bytes match58843e15. Additive evidence:
  `.omo/evidence/warehouse-workorder-asset-provenance/task-18/qa-state/DoneClaim.md`
  and `verified-results.json`. Checkbox remains unchanged for independent review.
- Task19 effects are zero. Next action is task19 customer installation episode schema M04; task19 remains unchecked.
