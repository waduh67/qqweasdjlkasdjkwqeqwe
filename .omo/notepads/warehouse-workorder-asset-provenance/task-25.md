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
