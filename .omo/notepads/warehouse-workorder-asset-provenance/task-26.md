# Task26 — returns, inspection and repair (in progress)

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
