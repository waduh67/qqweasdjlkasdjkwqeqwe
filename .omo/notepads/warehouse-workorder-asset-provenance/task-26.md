# Task26 — returns, inspection and repair (in progress)

##126 applied — RMA acceptance3 and ordinary regression still running

V175.126 has applied in the running integration test and is IMMUTABLE. SHA256
`cd894209c09945e323c4cc80f397f6857bde25d7f066aad5071e28e57bda0e30`. Main/test compilation passed. All3
WarehouseCustomerRmaAcceptanceIT cases passed: signed non-posting acceptance with
CUSTOMER titleRevision0 (including public ownership read), cross-purpose mint-key
conflict409 and history decoding both normal/RMA sources.

Shared `.omo/runtime/rma-acceptance-green.sh` is still running, same-name log and
archive `task26/rma-acceptance-green/xml`. CustomerAssetOwnershipIT simultaneous
acceptance race has FAILED; most other observed cases passed. Read final XML and
response details before fixing. Source audit: new custodyView invokes a multi-read
DB validator BEFORE work-order/assignment serialization; previous preview did not.
Do not remove validation; investigate moving validation after the normal locks.
The read-only retained-pre125 origin validation is queued at script end and will
not run if Gradle fails, so run it separately after the lock if needed. The prior
red probe and fixed exact SQL are under .omo/runtime/rma-origin-upgrade-* and
rma-origin-upgrade-probe.sql.126 changes comparison only, no stored seal writes.

No127 declared. Next real probe: independently approved reacquisition after signed
RMA (source title revision0), then removal/inspection/reissue. Source audit finds
old title-request revision formula and current RMA-owner assumptions may reject it;
prove before forward correction. Also remaining actual vendor replacement and
packaged proof.26 and whole remaining plan stay OPEN. Save/push ordinary checkpoints
on work/warehouse-completion -> origin feat/warehouse-workorder, no main deployment.

##126 declared — accepted RMA and stable historical origin comparison

All3 acceptance probes failed as intended after valid RMA installs: signed
handover and normal-key reuse returned MALFORMED_REQUEST; public history failed
KotlinInvalidNullException decoding a RMA source as normal DeploymentSource
(receiptId missing).3 tests/1 suite/3 failures/0 errors/skips,1m31s; archive
`task26/rma-acceptance-red/xml`.

An additional READ-ONLY retained-data probe found a real125 upgrade regression:
pre125 immutable acceptance origin contains to_jsonb(execution) without the new
nullable RMA columns; live generated origin now includes them. App-role validator
rejected a genuine retained pre125 handover with TITLE_ACCEPTANCE_RECONCILIATION_REQUIRED.
Private command `.omo/runtime/probe-rma-origin-upgrade.sh`, log
`.omo/runtime/rma-origin-upgrade-red.log`, fixed probe SQL same stem. Transaction
only reads and rolls back/disconnects; no stored evidence was changed.

Reserve V175_126__warehouse_rma_customer_acceptance.sql BEFORE creation. Add
non-posting signed RMA acceptance with CUSTOMER unchanged, exact witness/origin
seal and original customer. Normalize ONLY absent/null RMA extension columns on
both sides of the historical execution-origin comparison; preserve every sealed
byte and verify retained evidence unchanged after upgrade. All applied SQL through
125 remains immutable. Kotlin source readers distinguish shared custody data from
normal issue source; mint-key conflicts reject before decoding the wrong source.


##125 shared52 green — signed handover/key/history probes pending

Source567dc234 passed52 tests in3 suites, zero failures/errors/skips,4m47s:
real RMA reinstall, all48 CustomerAssetReplacementIT cases (including races and
SQL integrity) and3 RMA custody guards. Archive task26/rma-deployment-green/xml.
The unmatched CustomerDeploymentIT selector supplied no generic deployment cases;
those actual named suites still need the later shared regression.

Current checkpoint adds3 unverified WarehouseCustomerRmaAcceptanceIT probes:
non-posting signed customer handover, normal-vs-RMA mint-key conflict, and the
public inventory assignment history containing both execution source kinds.
The shared fixture first completes a real RMA installation for each probe.
Command `.omo/runtime/rma-acceptance-red.sh`, matching log, archive
`task26/rma-acceptance-red/xml`, queued after52 green. Read results before editing
production. No126 declared/created. Applied125 and earlier immutable.

API guide docs/warehouse-returns.md now covers actual material settlement and RMA
custody/authorize/install; signed acceptance and remaining vendor replacement/
reacquisition/packaged proof stay explicitly unfinished.26 stays OPEN.

##125 applied — original-customer RMA installation passed, shared run pending

V175.125 is APPLIED AND IMMUTABLE, SHA256 `61ce87ff20a7fcf0fc5eafc85c6618223a781074a853f6d0939b51d98eb5c00a`.
Read-only owner query during this run returned175.124|t and175.125|t. Normal
main/test compilation passed. Core WarehouseCustomerRmaDeploymentIT PASSED:
real acknowledged repair reinstalls the same physical asset for original customer,
CUSTOMER title retained, null issue, two historical assignments/ONU episodes,
one active episode, zero ISP availability and exact install replay.

RmaDeploymentSource is explicit; no fake material receipt/plan/issue IDs. Common
execution stores nullable receipt/plan ONLY for sealed RMA handover source, with
captured CUSTOMER asset/original assignment/SKU. Shared deployment result/document,
physical posting and episode validators remain in force. Inventory routes RMA
intent/consume to a dedicated service; WO owner validates REPAIR and current
assigned technician. Common mint/consumption/document writers are reused.

Current `.omo/runtime/rma-deployment-green.sh` and matching log/archive
`task26/rma-deployment-green/xml` still running custody guards and replacement
regressions. NOTE selector '*CustomerDeploymentIT*' matches no current class;
do not claim generic deployment coverage from that selector. Correct class names
are CustomerDeploymentFinalStateIT/ForwardFixIT/StrictInputIT/RootInputIT/
TwoCustomerIT/UpgradeIT, plus CustomerWarehouseProvenanceIT. Run appropriate
shared source/graph cases after the remaining RMA compatibility changes.

Next probes/fixes: signed customer handover for already-CUSTOMER RMA must be
non-posting (existing handover source loader assumes normal issue and PSB);
normal/RMA mint-key collisions and public assignment-history source decoding.
These are source-audit follow-ups, not yet reproduced tests. Then vendor
replacement, approved reacquisition and full packaged proof.26 remains open.

##125 declared — acknowledged RMA deployment

Custody guards passed3/3. Original-customer install baseline failed at authorize
with MALFORMED_REQUEST after a genuine repaired/inspected/received RMA handover.
Combined4 tests/2 suites/1 failure/0 errors/skips,1m30s; archive
`task26/rma-deployment-red/xml`. No physical install was attempted past the failure.

Reserve V175_125__warehouse_customer_rma_deployment.sql BEFORE creation. Preserve
common authorization/result/document/ONU guards; add an explicit handover-backed
execution source, with no material receipt/plan/issue IDs. Capture its real
CUSTOMER physical position and original closed assignment. Serial use stays
historically on the original issue; RMA does not claim another ISP material unit.
Normal issue-backed paths retain their existing receipt/plan bindings. All SQL
through175.124 remains immutable. Current read-only applied function capture is
`.omo/runtime/rma-current-functions.sql` (no data/credentials).

## Applied124 verified; RMA installation baseline and custody guards running

V175.124 is APPLIED AND IMMUTABLE, SHA256 `2c246b66a003534f27816277606f95447fb5d16de3f4efa0db5831bba246b2d2`.
RMA handover, supplier LOAN/SALE and ModularityTests passed6 tests in3 suites,
zero failures/errors/skips,2m23s. Archive task26/rma-handover-green/xml. Source
8ccd89d2 was pushed to feat/warehouse-workorder.123 remains immutable/79 green.

This checkpoint adds a shared real repair/REPAIR-WO RMA fixture, three custody
guard cases (wrong serial/work type, revoked receive/read replay, app-role source
rewrite/fabricated non-posting receipt) and an original-customer reinstall probe.
The latter expects purpose RETURN_CUSTOMER_RMA with null issue, then one actual
CUSTOMER installation/new ONU episode, old history retained and exact replay.
All4 tests are unverified: `.omo/runtime/rma-deployment-red.sh`, matching log,
archive `task26/rma-deployment-red/xml`. Check actual outcomes before production
changes. No125 declared or created yet. Current production remains8ccd89d2.

Next: bind RMA authorization/install to this acknowledged handover without a
fabricated ISSUE, preserve original sale title and episodes; then actual vendor
replacement, approved reacquisition and packaged proof.26/whole plan still OPEN.

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

## RMA custody124 declared before creation

Reserve V175_124__warehouse_customer_rma_handover.sql for a dedicated document
binding a closed inspected CUSTOMER repair to the original customer and an
assigned REPAIR work order. Separate warehouse dispatch and technician receipt,
immutable source/requests, exact two-leg posting and tenant-scoped final guards.
No fabricated normal ISSUE, stock origin or ISP availability. Installation permit
and original-customer reinstallation follow this custody slice. New baseline
WarehouseCustomerRmaHandoverIT is queued as rma-handover-red.sh/log; pending.


##123 first bootstrap rollback — corrected before first apply

The first green attempt failed before scenarios: PostgreSQL42601 rejected the
reserved alias authorization in123. Flyway explicitly logged19:25:56.313JKT:
"Changes successfully rolled back." Raw XML task26/material-obligation-green/xml.
123 was never applied; alias corrected to permit. No applied SQL edited.
Second run material-obligation-green-second.sh/log (same archive stem), includes
all79 serial/integrity/lifecycle/rework/closure cases. Results pending.


## Serial settlement and complete history —123 declared before creation

V175.122 passed the full material lifecycle/rework/return closure regression:
76 tests in14 suites, zero failures/errors/skips,4m51s. Archive
`task26/return-settlement-green/xml`; owned lifecycle exited0, volumes retained.

The next real baseline ran3 tests in2 suites and failed all3 (zero errors/skips,
1m17s): LOAN/SALE actual installed issue unit reports used0 instead of1, and a
new app-role CLOSE with an empty snapshot set is accepted after valid inspection.
Archive `task26/material-obligation-red/xml`; omission was savepoint-rolled-back.

Reserve `V175_123__warehouse_deployed_material_settlement.sql` BEFORE creation.
Count actual bound APPLIED deployment once against its original issue line;
title handover must not count another use. Seal the complete source-key set for
new lifecycle snapshots at transaction end; preserve historical snapshots and
all existing closure fences. All migrations through175.122 remain immutable.


## Applied122 — core closure passed, shared regression still running

V175.122 applied successfully and is immutable, SHA256 `97b18a1b9ffbd7eac315da93788df8d572575b001373b31700277557b0edd0c4`.
WarehouseReturnSettlementIT passed the real17.5m closure/replay and quantity
preservation case. The complete lifecycle/rework run is still running at
`.omo/runtime/return-settlement-green.sh` (log same stem); do not infer the final
count from this one pass. Archive destination task26/return-settlement-green/xml.

This test checkpoint adds WarehouseMaterialSerialSettlementIT (LOAN/SALE actual
deployment must count one used issue unit before/after handover) and
WarehouseMaterialSettlementIntegrityIT (app-role close cannot omit all historical
material lines after accepted inspection). Both are unverified probes, not proven
bugs yet. Their command `.omo/runtime/material-obligation-red.sh` is queued under
the shared host lock after lifecycle/rework. Log same stem; archive destination
`task26/material-obligation-red/xml`. The omission probe always rolls back its
savepoint, including if the invalid close is accepted. Inspect actual failures
before changing production. No code change sincec40bc0a1; no123 declared/created.

After these results, correct reproduced issues with a new declared forward SQL
version above175.122, then finish task26 replacement/RMA/reacquisition and shared
packaged proof.26 and the whole remaining plan stay open.


## Inspected material closure implementation checkpoint — tests running

Sourceb78489df passed read/access4 tests in4 suites, zero failures/errors/skips,
1m59s: scoped list counts and pages after two real residuals, paged repair history,
return and repair revoked replay. Archive task26/return-reads-green/xml.

WarehouseReturnSettlementIT then reproduced the outstanding bug: after actual
17.5m accepted inspection, expected0 but actual17500;1 failed,0 errors/skips.
Archive task26/return-settlement-red/xml. The earlier DAMAGED inspection and close
rejection passed; the failure occurred at post-acceptance outstanding read.

Declared V175.122 adds a separately derived settled return quantity from exact
acknowledged origin, terminal accepted inspection and matching APPLIED inbound.
Historical returned_base and conservation remain unchanged. Nullable response
settledReturnBase is absent for zero/old payloads. Lifecycle snapshots bind the
settled amount and outstanding; insert/final close checks retain reservations,
accountable quantities and rework-demand fences. Close still posts no stock.
Material summary now reads real CLOSED lifecycle instead of a constant OPEN.

Normal main/test compilation passed. Current command
`.omo/runtime/return-settlement-green.sh`, matching log, archive
`task26/return-settlement-green/xml`, runs new closure plus material lifecycle and
rework suites. Results and first122 applied status pending; inspect logs before
changing SQL. Existing175.121 and earlier stay immutable. Task26 remains OPEN.

Source-audit follow-ups requiring reproduced tests: serial deployment is not yet
included by old warehouse_material_obligation_totals used_base; check real serial
lifecycle totals. Old lifecycle final guard only counts supplied snapshots vs
body lines; test omission of all lines after accepted return before altering it.
Then continue vendor replacement, original-customer RMA, approved reacquisition,
packaged proof and remaining plan. No new feature closure is claimed here.


## Material closure declaration — next step

Reserve V175_122__warehouse_inspected_return_settlement.sql BEFORE creation.
Current source still computes outstanding as accountable+returned even after
accepted inspection. Preserve historical returned quantity/conservation and
add a separately proved settledReturnBase. Snapshot/header and close validators
must use the same sealed inspection evidence. Do not weaken unresolved or
damaged quarantine, reservations or rework-demand close guards.

New WarehouseReturnSettlementIT exercises17.5m acknowledged return, damaged
inspection/denied close, serviceable release, zero outstanding with returned
17500 retained, non-posting close and exact replay. Current baseline command
return-settlement-red.sh/log, archive task26/return-settlement-red/xml; pending.
Read actual failure before claiming reproduced defect. No122 SQL created yet.

## Scoped return list/history implementation — validation pending

Supplier repair access/integrity, ModularityTests, WarehouseContractTest and
MaterialWarehouseContractTest passed24 tests in5 suites with zero failures,
errors/skips,1m32s. Archive task26/repair-guards/xml; lifecycle exited0, volumes
retained. This completes those guard checks, not all of task26.

New read tests initially failed compilation because Jackson JsonNode.map shadows
Kotlin iterable map; using asSequence fixed the fixture. No feature-red claim for
that compile run (its copied XML may be stale). Correct baseline then failed2/2:
list GET405 after two real residual intakes in one tenant; history ignored size2
and returned6 revisions after a real supplier round trip. Archive
`task26/return-reads-red-second/xml`, zero errors/skips.

Current implementation adds SQL-scoped list totals/pagination/filtering using the
existing effective-area/site/location ancestry gates, plus bounded history pages.
No SQL migration added or changed; ceiling175.121 immutable. Normal Kotlin/test
compilation passed. `.omo/runtime/return-reads-green.sh` currently tests these
reads plus prior return and repair access checks; matching log and archive
`task26/return-reads-green/xml`. Final results pending; do not claim success yet.

Next: inspected material return obligation closure, actual vendor replacement,
original-customer sold RMA and approved reacquisition; remaining regressions and
packaged proof.26 remains open; continue whole plan after completion.


## Supplier repair verified — current checkpoint

Corrected first-application121 passed12 tests in6 suites, zero failures/errors/
skips,1m36s. This includes both LOAN/SALE vendor dispatch/receipt, wrong-serial
and premature inspection denial, exact replay, separate post-repair inspection,
unchanged physical identity/customer title, plus all10 prior return scenarios.
Archive task26/repair-green-second/xml. Owned lifecycle exited0 and retained
volumes. V175.121 is NOW APPLIED AND IMMUTABLE, SHA256 `2ffd54f2adb0668687daff40ac43a98ea300d2728b366d35a03d52f0982cb7a4`.
The only in-place change was its parenthesized CASE before any successful apply;
first failure's explicit Flyway rollback evidence is preserved below.

This checkpoint adds two unverified guard scenarios and a shared repair fixture:
WarehouseSupplierRepairAccessIT (revoked scope blocks original JWT/key replay)
and WarehouseSupplierRepairIntegrityIT (vendor reference immutable, fabricated
receipt without physical posting denied; legitimate receipt/inspection closes
case). Current host command `.omo/runtime/return-repair-guards.sh`, matching log,
archive task26/repair-guards/xml. Also runs ModularityTests and WarehouseContractTest.
Read final outcomes. No new production change after12-test success except docs.

Next: physical vendor replacement with verified new receipt; purpose-bound
original-customer RMA handover/acknowledgement/authorization and installation,
independent title reacquisition, bounded lists, material closure and packaged
proof.26 remains open. Declare new migrations above175.121 before creating them.


## Repair bootstrap correction —121 never applied in first run

The first implementation run compiled normally but all12 tests failed at
Spring bootstrap, before feature execution. PostgreSQL42601 at121 line116:
PL/pgSQL needs the CASE expression parenthesized inside the IF condition.
Flyway explicitly logged at19:01:43JKT: migration175.121 failed and "Changes
successfully rolled back." Therefore121 never applied and its syntax is fixed
in place; no earlier applied migration changed. Raw XML retained at
task26/repair-green/xml. Second run: return-repair-green-second.sh/log, archive
task26/repair-green-second/xml. Check actual result and applied status.

## Supplier repair implementation checkpoint — not yet verified

Source3bde6567 passed the complete return suite:10 tests/5 suites/zero failures,
errors or skips in2m37s. Archive task26/position-correction-second/xml. This
includes unposted rewrite rejection23514, atomic delete/rebuild with no movement,
17.5m residual, revoked replay, LOAN/SALE inspection and actual new-customer reuse.
The supplier repair baseline then failed2/2 at missing repair-dispatch route404,
after legitimate LOAN/SALE recovery; archive task26/repair-red/xml.

Current implementation adds repair-dispatch/repair-receive on the linked RETURN,
plus a sealed repair case, vendor custody, same-serial receipt back to quarantine,
immutable original title and separate post-repair inspection. The nullable repair
view field is omitted when absent so earlier persisted JSON stays byte-compatible.
V175.121 was declared before creation; pending first application. No earlier SQL
bytes changed. Read actual runner output before treating121 as unapplied/editable.

Current command `.omo/runtime/return-repair-green.sh` runs supplier repair plus
all10 return tests; matching log; archive task26/repair-green/xml. Compilation
and tests are pending. If a migration has applied, any correction must use a
new declared version. No repair success/whole-task26 closure is claimed here.
Still required: repair access/SQL attacks, supplier physical replacement,
original-customer RMA, independently approved reacquisition, bounded lists,
material obligation closure, full regressions and packaged HTTP.


## Supplier repair declaration and failing-first scenario

Reserve V175_121__warehouse_supplier_repair.sql BEFORE SQL creation.
WarehouseSupplierRepairIT exercises legitimate LOAN/SALE removal and inspection,
vendor outbound custody, exact replay, wrong-device denial, physical receipt
back into quarantine and a separate reset/inspection. Title and old assignment
must survive. `.omo/runtime/return-repair-red.sh` is queued behind the current
return regression; archive task26/repair-red/xml. Read its actual result before
claiming a missing-feature failure. This does not yet test original-customer RMA
delivery or supplier replacement. The draft implementation must not contaminate
the baseline test resources before its compile/runtime snapshot is taken.

## Position correction checkpoint — validation in progress

V175.120 applied, SHA256 `c76419bc97d7fecd225f6f5e9e393f8b71ab92ffa308e3b7488426e736bed9d0`. Never edit its bytes.
The recovered-asset continuation now checks ledger net quantities by full
physical dimension, latest inbound status and the one positive asset position.
Atomic projection deletion/rebuild passed. Initial corrected integrity test
rejected23514 as intended but failed at the outer commit because its expected
exception crossed Hibernate doReturningWork and marked rollback-only. Catching
and rolling back inside the JDBC callback fixes the test, not product behavior.
The second complete return run is queued/running under the shared QA lock:
`.omo/runtime/return-position-correction-second.sh`; matching log; XML archive
`task26/position-correction-second/xml`. Preserve first run separately at
`task26/position-correction/xml`. Inspect full results before claiming success.
No complete task26/repair/RMA claim. Actual new-customer reuse and access checks
already passed separately against prior source; now rerunning with the guard.


## Proven recovery position gap — forward correction declared

At f6331db6, real second-customer reuse passed1/1 with no product change.
Access revocation/restoration with original JWT and replay keys passed1/1.
The app-role integrity probe failed1/1: jointly changing asset and balance to
DAMAGED/QUARANTINE without any posting was accepted after a valid return.
The SAVEPOINT probe rolled back, so the test did not retain altered stock.
Archived XML: task26/red-reuse/xml and task26/access-integrity/xml; no errors/skips.

Reserve V175_120__warehouse_recovered_position_ledger.sql BEFORE creation.
Require returned-asset continuation to reconcile the positive physical position
and all net quantities with immutable APPLIED movement legs, preserving lawful
reuse and projection rebuild. Prior migrations through175.119 are immutable.
Rerun all return suites plus meaningful projection/history regressions after fix.

## Current step — recovery regression complete, reuse/integrity probes

Production checkpoint `6868f1a8` passed179 tests in7 suites, zero failures/errors/
skips,8m28s: CustomerAssetReplacementIT (including inherited guards/races),
CustomerAssetOwnershipIT, CustomerWarehouseProvenanceIT,
WorkOrderMaterialLifecycleITReturns, ModularityTests and WarehouseContractTest.
Ignored full XML: `task26/recovery-regression/xml`. Owned lifecycle exited0 and
removed only its containers/processes, retaining volumes.

This test checkpoint adds unverified next-step scenarios, not product success:
- WarehouseReturnITReuse: legitimate return/reset, new customer and WO,
  reservation/issue/acknowledgement and installation with the same physical ID.
- WarehouseReturnITAccess: original JWT/key replay after revocation/restoration.
- WarehouseReturnITIntegrity: app-role attempt to rewrite asset and balance
  condition without a posting after a valid return. A savepoint always rolls
  the probe back; it must reject with23514. No bypass is assumed proven yet.
- WarehouseReturnAssetFixture reuses the already verified receipt/reset journey.

Current host commands in order under the shared QA lock:
1. `.omo/runtime/return-reuse-red.sh`, log `return-reuse-red.log`, archive
   `task26/red-reuse/xml`; currently running/compiling all new test source.
2. `.omo/runtime/return-access-integrity.sh`, log `return-access-integrity.log`,
   archive `task26/access-integrity/xml`; queued behind reuse,600s bounded lock.
Read actual final outcomes; correct fixture/compile issues separately from
product findings. Do not claim these new cases passed until verified. No
production or SQL change since6868f1a8; ceiling175.119 remains immutable.

After these results, fix proved reuse/current-position issues with declared
forward migrations, then proceed to repair/vendor replacement/RMA/reacquisition,
scoped bounded list, material obligation closure and real packaged proof.26 is open.


## Latest checkpoint — recovered asset receipt and inspection

Published base `62139d01`; this checkpoint adds ASSET_REMOVAL intake with an
atomic open0/physical receipt1, immutable capture of recovery asset/balance and
independent receiving actor. The old removal permits live position changes only
through this sealed receipt, retaining original customer/ONU/title evidence.

Actual LOAN/SALE tests first failed2/2 at unsupported ASSET_REMOVAL intake after
legitimate handover and dismantle. Initial implementation applied175.118 but
failed both asset cases on ambiguous SQL origin; four residual tests passed.
Forward175.119 qualifies the column. Corrected run passed6 tests in2 suites,
zero failures/errors/skips, with exact receipt/inspection replay. Failed reset,
wrong serial and CUSTOMER-to-available release are rejected. LOAN becomes
AVAILABLE/SERVICEABLE/ISP; SALE stays QUARANTINE/SERVICEABLE/CUSTOMER and adds zero
ISP availability. One ended assignment remains. This is release proof, not yet
a complete second installation, supplier repair or original-customer RMA proof.

Applied immutable additions:
-175.118: `1c22a6ebf5eadc6050f45569d9e2b51787123c79da4c163d3346ef14aa8dcdfe`
-175.119: `ecf1bbe749782a18d1ecf910e6f3ec4f0657827d2bfc8db5e70d4bd5d7560db8`

Current host command `.omo/runtime/return-recovery-regression.sh`, stdout log
`.omo/runtime/return-recovery-regression.log`. It runs customer replacement,
ownership, source validation, residual returns, modularity and contract tests.
Archive: ignored `task26/recovery-regression/xml`. Wait for complete counts;
there is no regression PASS claim yet. The previous six-test owned lifecycle
exited0, stopped containers/processes and retained volumes. Full phase XML under
`task26/green-assets-second/xml`.

Next after regression: actual second loan issue/install (V175.83's asset-only
removal lookup needs assignment-specific handling), supplier repair and vendor
replacement, sold RMA original-customer authorization and approved reacquisition;
scoped lists/replay, adversarial SQL/physical continuity, material closure and
packaged HTTP.26 stays open. Declare any new migration above175.119 first.


## Latest checkpoint — verified residual inspection slice

Normal compilation and `qa.sh server --tests '*WarehouseReturnIT' --no-parallel`
passed4 tests, zero failures/errors/skips, in1m16s. Real HTTP/DB proves17.5m
remnant release: available900000->917500 MM, consumed82500 unchanged; original
segment ancestry retained; exact replay; unacknowledged/foreign/wrong origin,
missing permission, overmeasurement and invalid release location rejected;
concurrent inspection yields200/409 and exactly one physical posting.
Raw ignored XML: `task26/green-residual-second/xml`; host log
`.omo/runtime/return-green-second.log`. Lifecycle exited0, cleaned only owned
containers/processes and retained volumes. No packaged HTTP or task closure yet.

175.116 and175.117 applied and are immutable:
- `V175_116__warehouse_return_inspection.sql`: `a43d1b5ebe1ad5f43d7c17dade2757b26c5036c6cc2220cabd02b90756b7c6d1`
- `V175_117__warehouse_return_lifecycle.sql`: `2065110a31cdbcbceb19888db4291230108384e4de1265ad3469a3bb7e1ea1ff`

Next: recovered loan/SALE asset tests and linked receipt, reset evidence, supplier
repair, actual reuse and original-customer RMA, independently approved title
reacquisition; current-scope replay tests, app-role adversarial seals, scoped lists,
material obligation closure and shared regression. Do not mark26 complete.
Declare next migration above175.117 before creation. API currently accepts only
MATERIAL_RESIDUAL; do not claim device recovery or RMA delivery exists yet.


Source base: `af0471bb`; current local branch `work/warehouse-completion`, remote
recovery `feat/warehouse-workorder`. The user authorizes sustained completion
and ordinary checkpoint pushes. Task26 is not complete.

## Migration declaration before creation

Reserved `V175_116__warehouse_return_inspection.sql` above applied175.115.1.
Scope: immutable return source/intake and inspection operation binding, measured
quantities and origin-linked release. No earlier SQL bytes will change. Additive
asset recovery continuation/repair/RMA migrations will be declared separately
before creation and must exceed any already applied version.

## Implementation steps

1. Real residual-return17.5m fixture: legitimate use/return/acknowledgement, new
   inspection intake, measured release and replay. The new HTTP test initially
   targets the absent returns endpoint; run and preserve the failing result.
2. Receipt of recovered asset from its removal record, independent custody and
   history-preserving live-position continuation; loan inspection and reuse.
3. Supplier repair, replacement linkage, sold RMA original-customer authorization
   and independently approved reacquisition. No customer/unknown availability.
4. Scoped bounded reads/history, adversarial tests, packaged HTTP and durable notes.

Findings: existing residual acknowledgement remains its own sealed document at
revision2. New inspection must use a new linked document. Asset removal's current
DB guard pins its asset to recovery forever; replace only its live-position check
with a proven downstream receipt/history chain, retaining original evidence and
customer episode validation. Do not simply relax quarantine constraints.

## Initial implementation status

The first test failed in fixture setup: BIN used `parentId` instead of the API's
`parentLocationId`. This is not feature-red evidence. Corrected the fixture. A
proposed baseline rerun with compilation exclusions was refused by qa.sh before
tests; no runner rule was changed. Proceed with the normal complete compilation
and integration test. No failing-first product claim for this slice.

Implemented initial residual intake/inspection source, immutable origin record,
command/physical posting validation, strict source/quantity/condition handling.
New175.116 SQL has been created but not applied yet. Correct compilation or SQL
errors before any success claim. Asset recovery, repair/RMA, list and material
closure integration remain outstanding.

## First normal build — lifecycle correction

Compilation succeeded and175.116 applied. The test reached return intake and
failed409 because all inventory documents must start DRAFT; the initial new
inspection document incorrectly began RECEIVED_IN_INSPECTION. This is an actual
implementation failure.175.116 is now immutable. Before creation, reserve
`V175_117__warehouse_return_lifecycle.sql`: keep the new linked inspection
document initially DRAFT while its view explains already received quarantine;
allow only source-bound return cases to inspect from DRAFT, and bind the sealed
initial document state to revision0. Preserve every other document lifecycle rule.

## Asset recovery continuation design (not yet implemented)

Published residual checkpoint `62139d01`. New WarehouseReturnITAssets uses actual
LOAN/SALE handover, witnessed dismantle, warehouse receipt and reset checks. Run
`.omo/runtime/return-assets-red.sh` before the asset implementation; preserve its
result, avoiding any missing-route success claim.

Reserve before creation: `V175_118__warehouse_returned_asset_continuity.sql` above
applied175.117. Add ASSET_REMOVAL origin, capture the locked recovery asset/balance
as immutable intake evidence, validate open/receipt/inspection operation chain,
and permit the prior removal's live position to advance only through that
proven receipt. Retain old assignment, title, removal evidence and ONU history.
Do not let receipt flip CUSTOMER title or add ISP availability. Use an internal
open revision0 followed atomically by physical receipt revision1; residual
intake stays non-posting revision0. Avoid recursive removal/return validation.

Follow-ups: V175.83 currently selects recovery by asset alone; repeat removal
cycles will need assignment-specific lookup. Existing title/history validators
use removal's frozen source after assignment ends and must continue doing so.
Stock release is not yet proof of complete reissue/install or sold RMA workflow.

## Asset test result and forward correction

Genuine asset feature red:2/2 tests reached return intake after actual LOAN/SALE
handover and dismantle, then400 because ASSET_REMOVAL was not supported. The
implementation compiled and175.118 applied. Both asset tests then failed on a
PostgreSQL ambiguous `origin` identifier in the new validator; residual tests
still pass. Reserve before creation `V175_119__warehouse_return_asset_origin_scope.sql`
to qualify that table column. Keep applied175.118 unchanged. The failing run is
`return-assets-green.log`, not PASS evidence.
