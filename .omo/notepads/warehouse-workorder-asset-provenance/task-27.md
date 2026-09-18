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
