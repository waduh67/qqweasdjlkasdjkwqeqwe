# Task 25: Warehouse Transfers

## Checkpoint 1: prerequisite inspection

- Branch: `work/warehouse-task25`; local and live remote HEAD:
  `1f0564110a6a8a1d5361c8ab58c13dc7d71d08d8`. Initial worktree clean.
- Substep: read C1-C11, task25, child execution rules, contracts, migration
  manifest, learnings/issues, posting/receipt/issue/approval owner paths.
- Tests: not run yet; no implementation or passing claim.
- Migrations: none created or applied; namespace `175.113` reserved exclusively.
- QA: use this child's existing `scripts/warehouse/test-environment.sh` to
  generate its own private environment. No separately named child runner exists
  in this base. Preserve the inner lock and acquire the documented outer host
  lock for the complete lifecycle. Never copy another worktree's environment.
- Risks: generic transfers must not reuse the WO-specific receipt contract or
  release WO obligations. Shared approval effects currently name receipt/title
  actions; any task25 extension requires a minimal explicit owner integration.
- Next action: characterize existing receipt/posting/reservation behavior under
  the host lock, then run direct transfer HTTP tests against the missing API.
- Shared files changed: none. Plan, ledger, handoff and wave5 rules untouched.

## Checkpoint 2: baseline and fixture correction

- Branch/head: `work/warehouse-task25` at `1f0564110a6a8a1d5361c8ab58c13dc7d71d08d8`.
- Substep: existing receipt, posting reservation and current-authority baseline
  passed 10 tests, zero failures/skips, before production changes.
- Tests: first direct WarehouseTransferIT run executed 3 tests with 3 fixture
  failures at destination master creation (400 MALFORMED_REQUEST). This is NOT
  feature-red evidence. A root BIN requires a parent; fixture now creates a
  WAREHOUSE destination. No product validation was relaxed.
- Migrations: isolated Flyway validated 298 migrations through 175.112; none
  added. No SQL bytes changed.
- Cleanup: both runs stopped only child-owned containers/network; volumes kept.
  Task25 uses a private Gradle home, single-use daemon and in-process Kotlin
  compilation; process inventory after baseline showed no task25 JVM.
- Evidence: ignored task-25 directory contains host lifecycle script, archived
  baseline XML and timestamped direct-run log. LSP rejects paths outside tool
  cwd; actual Gradle Kotlin compilation succeeded.
- Risks/next action: re-run corrected HTTP fixture for genuine feature-red,
  then implement transfer persistence and owner services. No DoneClaim yet.

## Checkpoint 3: genuine feature red and migration declaration

- Branch/head: `work/warehouse-task25` at `815a1bf5b93240a79db84c6e861042af2022d594`,
  confirmed on the explicit live child remote.
- Substep/tests: corrected fixture completed real receipt and putaway. Exact
  WarehouseTransferIT ran 3 tests, all failing at POST /transfers (404 instead
  of 201), not compilation or fixture errors. Production remains unchanged.
- Migration declaration BEFORE creation:
  `server/src/main/resources/db/migration/V175_113__warehouse_transfer_bindings.sql`
  (Flyway `175.113`) for generic document transfer bindings and final-state guards.
  No SQL exists yet and prior migrations remain byte-identical.
- Cleanup: isolated stop/down succeeded before host lock release; retained
  private child volumes. Feature-red XML will be archived before the next run.
- Next action: own public contract, typed store/planner/service/controller;
  existing posting is the only physical authority. Generic transfer must reject
  encumbered and WO-issued stock in favor of the existing linked WO handover.

## Checkpoint 4: initial transfer path green (WIP, not DoneClaim)

- Branch/head before commit: `work/warehouse-task25`, `815a1bf5b93240a79db84c6e861042af2022d594`.
- Substep/tests: exact WarehouseTransferIT now passes 3/3, zero skipped. The
  actual HTTP-adapter/PostgreSQL journey proves draft no effect, dispatch100000,
  receive60000 leaving40000, final receipt, original partial replay after final,
  overreceipt rejection and post-dispatch cancellation rejection.
- Migration applied: `175.113`, file declared above; SHA256
  `9fc5a1a45f55fbd314e450c189c80a11ecd03de54ab83bda851354b4fad631bf`.
  Preserve it unchanged from here; corrections require a declared child version.
- Changed files: InventoryTransferApi; WarehouseTransferModels, Access, Planning,
  Service; WarehouseTransferStore, Stock; WarehouseTransferController;
  WarehouseTransferFixture, WarehouseTransferIT; V175_113 migration; this note.
- Shared delta: WarehouseHttpErrors adds only WarehouseTransferController to its
  advice registration. Posting, generic documents and approvals are unchanged.
- Cleanup: owned stop/down completed, child volumes retained, host lock released.
- Risks/next action: complete discrepancy approval integration, expanded hostile
  and concurrency tests, serialized identities, manual socket HTTP/SQL and
  clean artifact/regression proof. This initial green is not task completion.

## Checkpoint 5: discrepancy red and second migration declaration

- Branch/head: `work/warehouse-task25`, live `fbf18100f68f4e8646f6b6e4ca02adf21324e846`.
- Tests: expanded run passed 9 ordinary/adversarial/concurrent receipt cases;
  two discrepancy fixtures initially failed at malformed location JSON caused by
  trailing whitespace in the fixture helper. Corrected fixture now reaches
  /discrepancy and both LOST/REJECTED cases fail 404 instead of 200.
- Declare BEFORE creation: `V175_113_1__warehouse_transfer_discrepancy.sql`,
  version `175.113.1`, for linked independently approved remainder documents.
  Existing175.113 remains immutable; no higher version exists on this child.
- Substep/next: bind an ADJUSTMENT owner narrowly to transfer remainders; normal
  task28 disposition remains unsupported. Preserve sender/receiver independence,
  original receipt, exact residual and approval effect/outbox/inbox linkage.
- Cleanup: isolated stop/down succeeded before host-lock release, volumes kept.
- Risks: shared approval event/action dispatch additions must be reconciled with
  task27 by the integrator; no shared file edit from another child is copied.

## Checkpoint 6: quarantine continuity correction

- Branch/head: `work/warehouse-task25` at `fbf18100f68f4e8646f6b6e4ca02adf21324e846` plus discrepancy WIP.
- Tests: 11 passed including LOST/REJECTED approved remainder. Added five-phase
  rollback, partial-dispatch and revoked-token probes pass. A new rejected-stock
  follow-on transfer reproduced AVAILABLE100000 instead of60000: original status
  QUARANTINE was not included in destination eligibility. Correct eligibility
  requires original AVAILABLE as well as serviceable ISP title and eligible bin.
- Declare BEFORE creation: `V175_113_2__warehouse_transfer_quarantine_continuity.sql`
  (`175.113.2`) to enforce that same status invariant in the retained posting graph.
- Applied175.113 and175.113.1 remain unchanged. No higher source migrations on child.
- Cleanup completed before releasing host lock; all volumes retained.
- Next: rerun exact transfer plus posting/reservation/approval/modularity, then
  packaged manual HTTP and app-role SQL. No completion claim yet.
