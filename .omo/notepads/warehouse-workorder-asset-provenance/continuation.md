# Whole-plan continuation

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
