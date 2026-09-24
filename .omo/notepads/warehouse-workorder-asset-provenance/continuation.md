# Whole-plan continuation

## Current step — approval errors and cross-flow HTTP

- Latest published base is `cc96f215`. Two packaged attempts exposed harness
  issues: putaway requires an actual BIN under a warehouse; the HTTP helper must
  accept valid `application/problem+json` as well as `application/json`. Both
  are corrected. These failed attempts are not operational PASS evidence.
- The permission response was valid ProblemDetail, not an empty/non-JSON server
  response. It lacked the warehouse `FORBIDDEN` contract code. A new real-DB
  MockMvc regression first failed on the differing response media type. Scoped
  `WarehouseHttpErrors` now also handles Spring Security AccessDeniedException,
  returning the same403/FORBIDDEN body as domain permission denials. The new
  test has passed; the surrounding approval/count/transfer regression is running.
- Current host command is `.omo/runtime/integration-permission-green.sh`, log
  `.omo/runtime/integration-permission-green.log`. It runs the new error test,
  approval guards, all count tests and transfer guards, archives XML, then runs
  `qa.sh wave5`. Finish this command, correct any real HTTP failure, and rerun
  `qa.sh replenishment` before task closure.
- The HTTP receiver now has decision permission, so its rejected self-approval
  exercises independent-party enforcement rather than stopping at missing-role
  preauthorization. Failure cleanup preserves the owned server log. New runs
  remove stale phase outputs, preventing an old PASS from masking a new failure.
- No SQL or migration byte changed. All global completion checkboxes remain
  unchanged. Inspect the mixed AVAILABLE/LOST bulk transfer case when the HTTP
  journey reaches it; source selection may require an explicit balance identity.

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
