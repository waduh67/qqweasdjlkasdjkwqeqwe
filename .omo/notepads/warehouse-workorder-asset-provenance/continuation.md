# Whole-plan continuation

## Applied123 verified; RMA handover implementation pending validation

V175.123 is APPLIED AND IMMUTABLE, SHA256 `fb47bf2fd2c6bf6c13fb1bac13addccacf5ad20628c609f8bbd619dbad6f1dde`.
The corrected run passed79 tests in16 suites, zero failures/errors/skips,3m56s:
serial LOAN/SALE once-only usage, omitted-close-history rejection and full material
lifecycle/rework/return closure. Archive task26/material-obligation-green-second/xml.

RMA custody baseline failed1/1 at missing rma-handover route404 AFTER a real SALE,
removal, warehouse recovery, supplier round trip and reset inspection. Archive
task26/rma-handover-red/xml,1m18s, zero errors/skips. Its main/test compilation
completed before new sources were copied from private staging; baseline ran123.

Current source adds dedicated CUSTOMER RMA handover: closed inspected repair,
original customer/assignment, assigned REPAIR WO, independent warehouse sender,
physical dispatch to transit and technician acknowledgement, unchanged title.
Inventory calls a workorder-owned validation port. Reads retain access to closed
WO history; commands/replays require current WO revision/assignment and scopes.
Declared124 captures origin, seals exact requests/legs/outbox and immutable
custody history. No regular ISSUE or ISP availability is created. Installation
permit/reinstall, vendor replacement and approved reacquisition remain unfinished.

Run `.omo/runtime/rma-handover-green.sh`, matching log, archive
`task26/rma-handover-green/xml`: custody case, supplier LOAN/SALE and ModularityTests.
Compilation/result/first124 application are pending; inspect before editing SQL.
Do not edit any successfully applied migration. Task26 and remaining plan open.

## Material obligation correction — verification pending

V175.122 passed76 tests in14 suites (zero failures/errors/skips). New serial and
omitted-history probes then failed3/3 on real valid fixtures. Declared V175.123
counts bound APPLIED serial deployment once and seals complete lifecycle source
keys. Its first bootstrap failed42601 reserved alias and explicitly rolled back;
only never-applied123 was corrected. Second run is
`.omo/runtime/material-obligation-green-second.sh`, matching log and
`task26/material-obligation-green-second/xml`;79 selected tests, outcomes pending.
Check actual applied status before any SQL edit. Ceiling122 is immutable.
Continue task26 original-customer sold RMA, vendor replacement, independent
reacquisition, then remaining plan. No whole-task closure yet.

## Active task —26 returns/inspection/repair

- Latest:175.122 applied and is IMMUTABLE. Core17.5m accepted inspection/close
  test passed; full lifecycle/rework run still active in return-settlement-green.
  Two new unverified serial-quantity and missing-close-history probes are saved
  here and queued in material-obligation-red.sh under the same QA lock. Read the
  newest task-26.md and final logs before claiming outcomes or editing SQL.
- Supplier/return12 tests, repair guard/module/contracts24, scoped reads/access4
  all passed with zero failures/errors/skips in their respective runs.
- New material closure test reproduced outstanding17500 after accepted17.5m
  inspection (expected0). This checkpoint adds declared V175.122: separate
  settledReturnBase from sealed accepted return, preserves historical returned
  quantity, binds snapshots/header and retains other close fences. No new stock
  posting on close; material summary now reports actual CLOSED lifecycle.
- Main/test compilation passed. `.omo/runtime/return-settlement-green.sh` and
  matching log, archive task26/return-settlement-green/xml; new closure plus
  lifecycle/rework suites. Check full outcomes and122 applied status before edits.
- Earlier migrations through175.121 are immutable.122 is in its first run.
  Latest task-26.md has receipts and source-audit follow-ups (serial used totals,
  omitted lifecycle snapshot lines) requiring real tests before correction.
- Continue actual vendor replacement, original-customer sold RMA handover/ack/
  authorization/install, approved title reacquisition and packaged proof.26 OPEN.
  Whole remaining plan28/30–48/F1–F4 remains active;25/27/29 closed.
- Work on work/warehouse-completion, ordinary push to feat/warehouse-workorder;
  no main deployment, database reset or agent delegation.

## Current step — transfer/count integration verified

- Latest published base before this checkpoint is `d23bba73`. The permission
  correction passed27 tests with zero failures/errors/skips. Its subsequent
  real HTTP run exposed a product bug: one bulk identity can have both AVAILABLE
  and LOST positions, and multiple transfers can share a transit location.
- Three real-DB failing-first regressions reproduced ambiguous selection:
  count100->80 then dispatch, two simultaneous bulk transfers with independent
  receipts, and independent LOST remainder approval. Added optional
  `sourceBalanceId`; dispatch and receipt now match the frozen source/full
  document-owned transit dimension. Old payload hashes remain unchanged.
- Corrected source passed37 tests in10 suites, zero failures/errors/skips,
  including the three regressions, all count/transfer cases, permission errors,
  module boundaries and legacy payload hashing. `qa.sh wave5` also passed both
  packaged JVM phases with actual public signup and real HTTP: partial receipts,
  independent loss/count approvals, stale count/recount and tenant rejection;
  restart replay matched14 original responses and16 immutable read snapshots.
- JAR SHA256: `70c7ddb8f239cb553d71379c15d4a25742ae3e08859a9f8061695dc1f08f03da`.
  Ignored raw proof: `.omo/evidence/warehouse-workorder-asset-provenance/` +
  `integration-20260924/positions-green/{xml,proof}`. Regression/HTTP lifecycle
  completed and removed only owned containers/processes; volumes retained.
- Current host command is `bash .omo/runtime/integration-http.sh replenishment`,
  log `.omo/runtime/integration-replenishment.log`. It verifies the shared
  packaged lifecycle against task29 again. Finish it before closing25/27/29.
  Then implement26 returns/inspection/RMA, followed by28/30 and remaining plan.
- No migration byte changed; ceiling175.115.1. See `docs/warehouse-transfers.md`
  and `docs/warehouse-wave5-verification.md` for portable behavior and commands.
  Global checkboxes remain unchanged at this checkpoint. Newest entry supersedes
  all historical running/pending descriptions below.

## Recorded integration and harness checks

- Fresh combined regression finished117 tests,1 failure,0 errors,0 skips in34
  suites (9m53s). All14 count and15 transfer tests passed. The only failure was
  `WarehouseReplenishmentITUpgrade` expecting2 migrations after175.112 when the
  combined source correctly applies9. The unchanged production migration boot
  succeeded; the test stopped before its preservation assertions.
- Corrected the test to require both175.115 and175.115.1 among applied versions,
  while retaining second-run zero migrations and all original quantity/rule/
  acceptance-preservation assertions. Exact rerun passed1/0/0/0 in48s. This is a
  corrected focused result, not a claim of a fresh117-test aggregate rerun.
  Original reports are in ignored `integration-20260924/first/`; focused XML is
  in `integration-20260924/focused-http/xml/`.
- Current command: `bash .omo/runtime/integration-focused-http.sh`; after the
  focused test, it built a clean JAR successfully and is running `qa.sh wave5`.
  Review `.omo/runtime/integration-focused-http.log` and the owned server log.
  Complete/fix this HTTP proof, then rerun replenishment's shared lifecycle.
- Installed Playwright Chromium1234/Chrome151 plus its required Arch `alsa-lib`.
  A real headless Chromium launch and local-document evaluation passed. This
  verifies browser tooling only, not a warehouse browser journey.

- Integration checkpoint `675d5007` was pushed to `feat/warehouse-workorder`.
  The fresh combined regression is running against a newly generated owned
  environment; it has passed migration boot and is executing approval tests.
  No final combined test count/result is claimed yet.
- Added `qa.sh wave5`: clean packaged JAR, two real HTTP JVMs, public signup,
  actual IAM/area/master/receipt/putaway setup, partial transfer plus approved
  discrepancy, blind serial/bulk counts, independent variance, real transfer
  invalidating count approval, recount and original-response replay after restart.
  This harness is syntax-checked WIP and has not yet run. Its stock setup uses
  public HTTP only, with no SQL seeds or mocked business services.
- Reused the existing bounded HTTP helper and owned packaged-process lifecycle
  between replenishment and wave5. Rerun both modes after the combined regression
  finishes, preserving failure logs and correcting any reproduced problems.
- Source inspection initially suspected serial counts were excluded, but receipt
  admission actually creates a `SERIAL` inventory_segment with the asset's ID.
  No product fix was justified by that suspicion; the new HTTP scenario verifies
  unchanged serialized counts explicitly. A mixed AVAILABLE/LOST bulk identity
  may expose ambiguous transfer source selection; the new real flow tests it.
- Exact next commands inside one outer-lock/up/stop/down lifecycle:
  `scripts/warehouse/qa.sh wave5` and `scripts/warehouse/qa.sh replenishment`.
  `.omo/runtime/integration-verify.sh` is the current host-local regression wrapper;
  its ignored log is `.omo/runtime/integration-first.log`. Restore the portable
  command list below if the private wrapper is unavailable after recovery.

## Recovery position — 2026-09-24 integration checkpoint

- Remote recovery branch: `feat/warehouse-workorder`. Local working branch:
  `work/warehouse-completion`. Commits use normal explicit pushes; no main merge
  or deployment. The user requests continued work to completion and durable
  checkpoints, including portable notes for another agent after VPS loss.
- Sources imported with original commit references: task25 through `f4297aef`,
  task27 through `57bc533a`, task29 through `9ccd5bb0`. Global plan checkboxes
  remain1–24 complete;25–48 and F1–F4 pending. Source integration alone does not
  close the three child tasks.
- Resolved shared Kotlin conflicts by retaining all controller registrations,
  `ADJUSTMENT`/`COUNT` posting kinds, `TRANSFER_REMAINDER`/`COUNT_VARIANCE`
  business actions and `RETURN_RECEIVED`/`COUNT_POSTED` effect events. Reviewed
  automatic policy-source integration: both transfer-party and count-counter
  independent-approval exclusions remain present.
- Imported migrations175.113 through175.115.1 retain exact child bytes. Fresh
  combined database verification is next. Declare any correction above175.115.1;
  no lower child version may be added. No new migration is reserved yet.
- Task29 historical evidence:191 selected regression tests plus1 live seed,
  zero failures/errors/skips; two packaged HTTP sessions including restart replay
  passed. See task-29.md for artifact identity and reproduction. Task25's note
  records190 earlier passing tests but unfinished HTTP proof. Task27's latest
  code extends its older note; its full current test result must be established.

## Current verification procedure

Use JDK21 and the repository Gradle wrapper. Read `docs/warehouse-migrations.md`
for isolated Docker roles/endpoints and `scripts/warehouse/qa.sh` for validated
commands. On this host, all QA lifecycles hold the outer flock at
`/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock`.
Create the parent directory if recovering onto a new host. Hold it across up,
checks, Gradle/HTTP/browser, owned stop and environment down. Retain data volumes.
Never copy or print private environment credentials.

First combined checks: `qa.sh server` with `--tests '*WarehouseTransferIT*'`,
`--tests '*WarehouseCountIT*'`, `--tests '*WarehouseReplenishmentIT*'`,
`--tests '*WarehouseApprovalIT*'`, `--tests '*WarehouseContractTest'`,
`--tests '*ModularityTests*'`, `--tests '*WarehouseSchemaITUpgrade*'`,
`--tests '*WarehouseEnvironmentIT*'`, and `--rerun-tasks --no-parallel`.
Archive the actual XML/counts before subsequent runs overwrite them. No combined
result exists at this checkpoint. Correct reproduced failures before claiming
completion; complete packaged HTTP proofs and add missing requirement coverage.

## Remaining sequence

Close25/27/29 after combined proof; implement26 returns/inspection/repair,
28 loss/scrap/adjustment,30 reports. Then31–41 operational web screens and real
browser journeys,42 shared KMP feature,43 preservation/cutover,44 adversarial
durability,45 complete real browser journeys,46 full regression,47 runbooks,
48 CI and F1–F4. Follow the active plan's business invariants. Hardware GPON
certification remains explicitly deferred by the owner's earlier decision.
