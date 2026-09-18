# Task 27: Blind Stock Counts

## 2026-09-18: WIP initialization

- Status: WIP; not a DoneClaim, not reviewed or integrated. No task checkbox changed.
- Worktree: `warehouse-wave5-task27`; local and remote branch: `work/warehouse-task27`.
- Verified clean starting HEAD and live remote: `1f0564110a6a8a1d5361c8ab58c13dc7d71d08d8`.
- Read the full child plan, C1-C11, task27, learnings/issues, contracts, migration manifest and Wave 5 execution rules. No AGENTS.md was found in this child worktree or its ancestor locations.
- Scope: count sessions, blind immutable observations, recount, dimension-revision checks, independent existing approval dispatch, and existing posting authority only. Tasks25/26/28/29 and all UI/mobile work are excluded.
- Migration reserved BEFORE creation: `server/src/main/resources/db/migration/V175_114__warehouse_blind_count_sessions.sql`. This name is reserved, not created or applied. Any necessary child migration must be named here before creation and before higher global versions apply. Earlier SQL bytes remain unchanged.
- Existing foundations: `inventory_cycle_count` already has document/revision/identity/balance/observed-revision/exact-quantity/counter/approval/operation columns in V175 and V175.2. Extend those facts rather than creating another count ledger.
- Shared integration constraints: task27 owns minimal approval dispatch extensions in `DurableApprovalService`; use `InventoryApprovalApi` and `warehouse.approval.effect`. Receipt/title owners and task25/29 remain independent.
- Tests: none executed yet; no baseline, feature-red, HTTP, database, concurrency or build success claimed.
- Current step: characterize existing approval/source/posting revision behavior in the child-private environment under the host-exclusive QA lock.
- Next action: run baseline source/approval tests, archive complete reports, clean only owned processes/containers, then add failing-first count HTTP/DB tests before production changes.
- Planned local evidence: `.omo/evidence/warehouse-workorder-asset-provenance/task-27/`; never stage evidence, private environment, generated reports or runtime files.
- QA lifecycle: bounded outer `flock` on the prescribed Wave 5 host lock; retain the runner's inner lock; child-generated private marker/Compose project/volumes only; trap cleanup and retain volumes. Release the host lock before coding or research.
- Changed-file delta at this checkpoint: this task note only.

## 2026-09-18: Baseline and first count red

- WIP HEAD: `9c980259df5a9c66609253d36bb3f5de0fa73644`, pushed and live-verified on the named child branch.
- Baseline passed 16 tests: WarehouseApprovalITSource 4, WarehousePostingITReservations 5, WarehouseEnvironmentIT 7; zero failures/errors/skips. Full XML is archived locally as `task-27/baseline-xml.tar.gz`.
- Original count feature-red compiled and executed 3 tests, all failing: two real authenticated MockMvc requests reached missing counts route (404 rather than 201), one app-role database assertion found no immutable-count guard. Receipt, stock and assigned-counter fixtures succeeded first. This is feature red, not compilation/fixture failure.
- First implementation run compiled but failed database initialization with SQLSTATE42601 at the new scope guard's CASE comparison. V175.114 rolled back and was NOT applied; parentheses were corrected before another attempt. That run is not feature-red or success evidence.
- All three QA lifecycles held the outer host lock with 7200s bounded wait and retained the inner runner lock. Child-private environment and Gradle home were generated independently. Each trap stopped owned processes, removed owned containers/network, retained both volumes and stopped the child-private Gradle daemon before release.
- LSP rejects this external worktree path; actual Kotlin compiler and PostgreSQL tests are the diagnostic authority, not an LSP-clean claim.
- Current step: validate the initial session/observation/no-change workflow, then extend count approval and stale-posting checks with failing-first tests. No completed-task claim.
- Next action: rerun exact `qa.sh server --tests '*WarehouseCountIT*' --rerun-tasks --no-parallel` after the unapplied SQL correction; preserve all prior migration bytes.

## 2026-09-18: Initial workflow green, approval work remains

- Exact WarehouseCountIT rerun: 3 tests, zero failures/errors/skips, real PostgreSQL and authenticated MockMvc; `count-second-xml.tar.gz` retains full XML. Baseline and original feature-red archives remain separate.
- V175.114 applied successfully and is frozen at SHA256 `e4894f4c0c23bd578510ea0940b7ba676b996131d0a0df2739f6008c80cdbbc4`. No earlier migration changed.
- Current implementation: public count contracts; strict JSON controller; scoped session/start/observe/submit/recount; immutable measured facts in the existing cycle-count table; original actor-bound receipts; blind reads; unchanged submission advances without any movement.
- Still WIP: variance approval/posting, final atomic revision validation, review DTO, final-state DB seals and broad adversarial/manual runtime proof are NOT complete. Existing approval owner does not yet execute COUNT.
- Changed-file delta: `InventoryCountApi.kt`, `WarehouseCountController.kt`, `WarehouseCountService.kt`, `WarehouseCountStore.kt`, `WarehouseCountReceipts.kt`, `WarehouseCountIT.kt`, `V175_114__warehouse_blind_count_sessions.sql`, one registration in `WarehouseHttpErrors.kt`, and this note.
- Next action: failing-first stale submission/recount and approved-variance tests, followed by the existing approval owner integration. No task checkbox changes or completion claim.
