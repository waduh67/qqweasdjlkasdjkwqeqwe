# Task26 — returns, inspection and repair (in progress)

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
