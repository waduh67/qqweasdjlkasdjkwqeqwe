# Task 29 - Replenishment

## 2026-09-18 - Child baseline and migration declaration

- Branch: `work/warehouse-task29`; delivery target: `HEAD:refs/heads/work/warehouse-task29`.
- Local and live remote head: `1f0564110a6a8a1d5361c8ab58c13dc7d71d08d8`; clean tracked and untracked worktree at entry.
- Scope: task29 only. Suggestions and internal requests never create purchases, payments, physical stock or ledger entries. Tasks25/27 and global plan/ledger remain untouched.
- Read C1-C11, task29, wave5 execution, learnings/issues, contracts and migration manifest. No AGENTS.md found in this child or its immediate parent directories.
- Existing V175 rule/request tables have SKU/unit composite FK, tenant RLS, revision guards, unique business keys and one PENDING request per rule. V175.2 adds the state/history index.
- Availability source: WarehouseQuerySql scoped_positions.available already subtracts OPEN reserved_unpicked_base and reserved_picked_base exactly once. Replenishment must reuse this projection policy.
- Before any SQL creation, declare `V175_115__warehouse_replenishment.sql` (Flyway 175.115) for rule target/lead-time/package snapshots, durable shortage windows, acceptance/reference binding and immutable operation history. No SQL exists or has been applied for this declaration yet. No predecessor or other namespace may change.
- Formula tests planned: exact EA/MM threshold, target deficit, checked package ceiling, reservations once, confirmed remaining inbound once, draft/cancelled exclusion, package overflow, stale rule/stock recheck, later shortage windows and replay authorization.
- Baseline characterization must run before production edits. Required runtime evidence is pending, not PASS.
- QA ownership: hold the bounded host flock plus existing inner runner lock for up/check/test/manual/stop/down/JVM cleanup; create only this child's generated private env and retain all volumes.
- Risks to verify: generic receipt destination semantics differ from transfer destinations; do not infer inbound from a draft or count a received quantity again. Acceptance requires a consistent transactional position, not independently timed aggregate reads.
- Next action: run unchanged projection/reservation/receipt characterization under the host lock, archive reports, then add failing task29 endpoint tests.
