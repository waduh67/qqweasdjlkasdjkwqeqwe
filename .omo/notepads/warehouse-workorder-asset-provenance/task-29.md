# Task 29 - Replenishment

## 2026-09-24 — combined verification complete

Source `af0471bb` on local `work/warehouse-completion`, published to
`feat/warehouse-workorder`. Imported task25/27/29 migrations are unchanged.
The combined focused suite passed37 tests in10 suites, zero failures/errors/
skips. Packaged `qa.sh wave5` passed actual public signup, transfer and count
journeys and fresh-JVM replay (14 responses,16 stock/document/history snapshots).
Shared `qa.sh replenishment` also passed both JVM phases, a separate live seed
1/0/0/0, stale acceptance and unchanged11 physical posting counts:
`[4,7,4,1,0,4,6,3,3,0,0]` before/after.
JAR SHA256 `70c7ddb8f239cb553d71379c15d4a25742ae3e08859a9f8061695dc1f08f03da`.
Both lifecycle runs cleaned owned containers/processes and retained data volumes.
Task-specific backend acceptance is complete; UI and final full-plan gates remain
separate. This is executor verification, not an independent reviewer signoff.
Portable reproduction: `scripts/warehouse/qa.sh wave5` and `replenishment` within
one owned up/stop/down lifecycle under the documented host lock. Raw evidence is
ignored under `integration-20260924/positions-green` and its replenishment archive.
Newest entry supersedes historical WIP descriptions below.


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

## 2026-09-18 - Expanded red and forward declaration

- Expanded exact filter executed12 tests,4 failures,0 skips. Two product failures: cancelled request excluded confirmed remaining receipt supply (expected40000, actual0); pending snapshot forgery committed instead of raising. Two fixture failures: BIN creation without parent and Hibernate-wrapped SQL rejection asserted as a direct SQLException. Scheduler/repeated scan/concurrent acceptance and current-scope replay denial passed.
- Debug hypotheses distinguished with archived XML: query state filtering erased real supply; snapshot presence guard did not validate source truth; fixture errors occurred before product behavior or despite correct SQL rejection. No debugging process/instrumentation was left running.
- Declare before creation `V175_115_1__warehouse_replenishment_snapshot_binding.sql` (175.115.1) to bind request snapshots to actual rule/quantity and validated receiving line/destination. Only175.115 has applied in this child; no higher source version exists here. Applied175.115 remains unchanged.
- Next action: retain the two feature failures, correct fixture setup/assertion, preserve cancelled confirmed supply, and apply the declared forward-only binding guard through isolated QA.

## 2026-09-18 - Expanded verification and contract caveat

- Published checkpoint `7fb8e8a6d5ea7577b2efc06d540fe32cc53ae86b`; expanded exact suite17/0 failures/0 skips. Actual generic transit fixture proves physical60000/reserved20000/available40000/inbound40000 -> request75000. A stale draft-line revision fixture error was fixed without product changes.
- Applied175.115.1 SHA256 `223d0fb34d4b5eb693308da07f8505f6c33570e24e285a3f0dd69cfcd9e28e4f`;175.115 hash remains unchanged. Forward upgrade preserves old rule/request values without invented acceptance.
- Explicit lock-contention test first failed with TimeoutException after8seconds. The task29 entry paths now set transaction-local lock_timeout2s and statement_timeout20s; the same test returns409. The timeout-red is retained as failure, never PASS.
- Full selected regression executed180/2 failures/0 skips. All18 task29 tests, query15, reservation26, receipt93, authority3, material contracts7, Modularity3 and schema upgrade3 passed. Two unchanged WarehouseContractTest expectations failed: missing customerId in its ConsumeDeploymentRequest fixture and missing observation in its WarehouseMutationMetadata expected fields. Those test/contracts have zero diff from base1f056411; they were not edited or weakened. Combined run remains FAILED, not an aggregate PASS.
- Remaining live work: exact18 rerun, clean bootJar and two real JVM HTTP sessions, physical equality for planning operations, separate real receipt/putaway stale-acceptance proof, final owned cleanup. Private task29-live.json is generated only by the legitimate integration fixture and removed after manual use.

## 2026-09-24 - Resume in the user's workspace

- Checked out `work/warehouse-task29` from `origin/work/warehouse-task29` at
  `c2f4089ca7ba3a4edfd2fe21835bce526a4e00cb` in
  `/home/fajar/ftth/qqweasdjlkasdjkwqeqwe`; the entry worktree was clean.
- Installed the requested development dependencies: JDK21, Docker/Compose,
  Node/npm, TypeScript LSP, official Kotlin LSP, jq and ShellCheck. Kotlin LSP
  answered a real initialize request and was shut down. `web/npm ci` succeeded.
  A private Gradle home uses JDK21 and single-use daemons; unrelated JVMs remain
  outside the QA lifecycle.
- Baseline reproduced37 tests/2 failures/0 errors/0 skips: all18 replenishment
  tests passed. Both failures were the previously documented stale
  `WarehouseContractTest` fixtures. Added the required `customerId` and
  `installationPayload`, and included the existing `observation` metadata field
  in the exact field assertion. Production contracts and authorization assertions
  were not relaxed. Original failing XML/logs are retained under
  `task-29/resume-baseline/` evidence.
- First creation of the isolated database exposed a readiness race: the temporary
  PostgreSQL initialization server accepted a Unix socket, then shut down while
  the runner initialized roles. The Compose health check now waits on TCP.
  A fresh generated environment passed startup and role/marker/extension checks;
  the first environment's private marker and both volumes were retained.
- Added `qa.sh replenishment` with `replenishment-smoke.sh` and
  `replenishment-http.py`: clean JAR build, legitimate fixture, two real HTTP JVM
  sessions, numeric/scope/replay checks, separate actual receipt/putaway stale
  acceptance, persisted resolution, physical API equality and11 posting counts.
  Cleanup removes private fixture/replay manifests and stops only owned processes.
- Shared-file delta: `scripts/warehouse/qa.sh`,
  `deploy/docker-compose.warehouse-test.yml`, and `WarehouseContractTest.kt`.
  Task-local additions are the two smoke scripts plus replenishment documentation
  and this note. No migration bytes, task25/task27 implementation, or global plan
  checkbox/ledger changed. Both175.115 migration hashes still match the preceding
  checkpoint.
- User requested a stop before running Codex inside screen. The final regression
  was deliberately interrupted by terminating only the owned single-use Gradle
  process group. Its daemon-disappeared message records this interruption, not a
  newly diagnosed product failure. The runner then stopped owned processes and
  removed owned containers/network, retaining both generations of volumes.
- Before interruption, the corrected12 `WarehouseContractTest` assertions,
  ModularityTests and environment isolation checks had passed. The full selected
  regression has no final result; packaged HTTP/restart verification has not yet
  run. Neither is an aggregate PASS. Partial output remains in
  `task-29/resume-verification.log`, separate from the original failing baseline.
- Resume by reviewing the working diff and running
  `bash .omo/runtime/task29-verify.sh` from this workspace. This private wrapper
  holds the host lock for the regression, report archival, packaged HTTP proof,
  and cleanup. Fix any reproduced failures and update this note with actual
  counts/artifact identity. The new smoke scripts remain unverified at runtime.
- All changes are saved locally and uncommitted on `work/warehouse-task29`.
  No commits were pushed. Development dependencies remain installed; Java21 is
  now the default Java runtime.

## 2026-09-24 - Completed local verification after resume

- The user's `lanjut` resumed the stopped work. The selected regression completed
  with **191 tests, 0 failures, 0 errors, 0 skips**, including all18 replenishment
  tests. Its49 XML reports are archived in `task-29/resume-regression/xml/`.
  The separate clean-build live fixture completed1 test with no failures/skips.
- Packaged HTTP verification completed successfully in two distinct JVMs. The
  physical60000/reserved20000/available40000/inbound40000 MM fixture produced one
  75000 MM request; identical acceptance replay survived restart. Changed payload,
  restricted user and foreign location were rejected. A separate actual HTTP
  receipt/putaway of100000 MM rejected stale acceptance with409 and preserved its
  fulfilled window and stock after restart.
- The eleven posting counts before/after planning and replay were both
  `[4,7,4,1,0,4,6,3,3,0,0]`; stock API responses also remained identical.
  JAR SHA256: `f465c71634d9fff60c7449b9b623afc1e169508eb8a7045c98488ff6de74af36`.
  Assertions, both server logs, counts and hash are archived in
  `task-29/resume-http/`; the successful lifecycle log is `resume-http-run.log`.
- Two smoke-runner issues were reproduced and fixed without changing production
  contracts: optional unconfigured SMTP made the default health endpoint DOWN;
  a socket without address reuse treated a recently released port as occupied.
  The test-only readiness group explicitly requires database, disk and ping UP.
  A real socket probe demonstrated the TIME_WAIT failure, successful reuse and
  continued rejection of an active listener. Failed attempt logs are retained
  separately; they are not reported as successful HTTP runs.
- Final shell syntax, smoke-script ShellCheck and `git diff --check` passed.
  Both175.115 migration hashes remain unchanged. Owned JVMs, containers, network
  and private fixture/replay/PID files are gone; ports25432/29000/17880 and the
  host QA lock are free. Test volumes and unrelated development JVMs remain.
- Task29's remaining local verification is complete. Changes remain uncommitted
  on `work/warehouse-task29`. Global task checkboxes, integration of tasks25/27,
  whole-plan release gates, commits and pushes are outside this completed run.

## 2026-09-24 - Durable checkpoint and whole-plan continuation

- The user now requests sustained completion of the remaining plan, with regular
  commits for recovery by another agent if this VPS is lost. This supersedes the
  previous run's limited scope and uncommitted status. The verified task29 fixes,
  executable HTTP/restart proof and this handoff are committed together and
  pushed normally to `work/warehouse-task29` before integration work starts.
- Tasks25 and27 have separate remote work. Inspect their latest notes and source,
  integrate in migration order, resolve shared-file differences, and run combined
  verification before changing global completion checkboxes. Continue tasks26,
  28 and30, then the web/mobile/cutover/regression/release work in the active plan.
- Portable task29 reproduction: use JDK21, Docker/Compose, Python3 and jq; create
  an owned environment with `scripts/warehouse/test-environment.sh up`, run
  `scripts/warehouse/qa.sh replenishment`, then `qa.sh stop` and the environment
  script's `down`. Hold the shared host QA lock for the entire lifecycle. Read
  `docs/warehouse-migrations.md` and `docs/warehouse-replenishment.md` for
  environment constraints. Never restore or commit private runtime credentials.
- The191-test result and exact JAR hash above are historical evidence for this
  checkpoint, not a claim that a later combined build or full plan has passed.
  Raw ignored evidence may be unavailable after VPS loss; rerun relevant checks.
