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

## 2026-09-18 - Initial verified slice, not a DoneClaim

- Branch/live checkpoint: `6def2040179f9cc966525263f76ba52957c11543`; seven product checkpoints were each pushed with the explicit child refspec after the initial note checkpoint. No other branch or worktree was written.
- Baseline: unchanged WarehouseQueryITBalances, WarehouseReceiptITReceive and WarehouseReservationITSupplyProjection executed 5/0 failures/0 skips before production changes. Feature red then executed 3/3 failures, all missing rule-route 404 assertions, with compiled tests.
- Initial green: exact WarehouseReplenishmentIT filter executed 5/0 failures/0 skips. A prior compiler error in Jackson sequence mapping was corrected and is not reported as feature red or PASS. Baseline/red/green XML remain separately archived under task-29 evidence.
- App-role preflight verified warehouse_app NOSUPERUSER/NOBYPASSRLS, existing rule/request columns and constraints, and live maximum 175.112 before applying 175.115. Applied `V175_115__warehouse_replenishment.sql` SHA256 is `6f3ebdb090c6cea31ce504933ac131c52a7403589d16eff8dee8cff154cbf66c`; its bytes are now frozen.
- Numeric initial proof: physical60000 MM, reserved20000, available40000, min50000/target100000/package25000 -> requested75000; acceptance/replay did not change physical stock or movement count. Restock100000 rejects stale acceptance and a later reservation opens a distinct window.
- Formula semantics: position=existing available+confirmed open inbound, never subtract reservations again; strictly below min triggers ceil((target-position)/packageMultipleBase)*packageMultipleBase. Package rounding may exceed the soft maximum. Lead time is planning metadata, not invented demand or receipt confirmation. Aggregate positions use exact BigInteger; request quantities stay checked Long.
- Current mutations use the existing exclusive current-authority fence, then topology and owned rows; this serializes normal stock commands and schedulers through commit. No authority epoch is incremented merely for a planning operation. Scheduler uses a durable per-tenant cursor and at most25 rules per transaction.
- Shared product edit so far: only WarehouseHttpErrors adds the new controller. No WarehousePostingService, prior SQL, task25 classes or global state was changed.
- Risks/remaining work: expanded rule/replay/scheduler/inbound/SQL adversarial tests, receipt-reference destination sealing, upgrade/regression, clean bootJar, actual live HTTP/restart proof and final cleanup. Existing LSP refuses external worktree paths; real compiler/test evidence is used instead.
- All completed QA lifecycles held the host lock including owned startup/check/stop/down/private Gradle shutdown. Containers and network removed, both child volumes retained. Host lock released before coding.
- Next action: run expanded failing-first supply and app-role snapshot probes; fix only reproduced task29 defects with forward migrations if needed.
