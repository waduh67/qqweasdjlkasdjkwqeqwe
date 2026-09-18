# Replenishment Internal

Task29 adds planning rules and internal requests under
`/api/v1/warehouse/replenishments`. It does not create a financial purchase order,
payment, receipt, movement, asset, or physical balance. Receiving remains an
explicit operation of the existing receipt API.

## Exact Position and Rules

Rules are unique per tenant/SKU/location. The location must be an active,
issue-eligible warehouse or bin within the actor's current warehouse and area
scope. The active SKU determines the immutable EA or MM base unit.

- `minimumBase >= 0`, `maximumBase >= minimumBase`.
- `minimumBase <= targetBase <= maximumBase`.
- `packageMultipleBase > 0`; it is the exact base-unit quantity of a package
  multiple, not a floating-point conversion or a mutable catalogue lookup.
- `leadTimeDays` is in `0..3650`. It is planning metadata; it does not invent
  forecast demand or confirm unreceived goods.
- Quantities are decimal integer JSON strings. MM fractions, exponent notation,
  negative values, JSON numeric quantities and values beyond signed Long reject.

The existing warehouse availability SQL is the sole eligibility policy:

```text
position = available + confirmedOpenInbound
available already excludes open reserved-unpicked and reserved-picked quantities
if position >= minimum: no new shortage
otherwise quantity = ceil((target - position) / packageMultiple) * packageMultiple
```

Reservations are location-bound commitments and are not subtracted a second time.
Unallocated backorders without a location are not assigned to a warehouse by
guesswork. Position aggregates use exact arbitrary-precision integers; the final
request must fit signed Long. Package rounding can exceed the soft maximum.

Confirmed inbound includes the net IN_TRANSIT ledger quantity of generic
DISPATCHED/PART_RECEIVED transfer lines destined for the rule location. It excludes
WO/customer-bound transfers, drafts and completed/cancelled documents. It reads
existing generic documents and paired legs, not task25 classes or tables.

A bound receiving reference contributes only inspected accepted quantity still
awaiting putaway. Putaway is already included in physical availability and is
subtracted from that inbound quantity. Cancelling an internal request does not
cancel its real receipt supply. Suggestions and unbound accepted requests are
never treated as real goods.

## Routes

All mutations require `Idempotency-Key`. Rule configuration and every mutation
require `inventory.request.manage`; reads require `inventory.request.view`.
Authentication, current roles, area and warehouse scope are rechecked for replay.
The same actor and identical input replay the original response. Changed input
with the same key returns409; another actor cannot retrieve it.

| Method | Relative Route | Body / Meaning |
| --- | --- | --- |
| POST | `/rules` | `skuId, locationId, baseUnit, minimumBase, maximumBase, targetBase, packageMultipleBase, leadTimeDays` |
| PUT | `/rules/{id}` | Same fields plus `expectedRevision`; identity cannot change |
| POST | `/rules/{id}/archive` | `expectedRevision`; cancel accepted pending request first |
| GET | `/rules`, `/requests` | `page=0,size=25`, optional `locationId,skuId`; size1..100 |
| GET | `/rules/{id}`, `/requests/{id}` | Current scoped record |
| GET | `/rules/{id}/history` | Bounded command history with page/size |
| POST | `/rules/{id}/recompute` | `expectedRevision`; persist or refresh one shortage window |
| POST | `/requests/{id}/accept` | `expectedRevision,expectedRuleRevision,quantityBase` |
| POST | `/requests/{id}/cancel` | `expectedRevision`; closes only the internal request |
| POST | `/requests/{id}/receiving-reference` | `expectedRevision,documentId,documentRevision,lineId` |

Recompute returns the current request when a window exists. With no window and no
shortage it returns `ruleId,position,request:null`, explicitly showing the checked
position. Request responses contain the captured position, rule/unit/package
snapshot and acceptance metadata. Accepted snapshots remain immutable, even when
later recomputations close the request or a rule changes.

Acceptance rereads current availability and inbound under transaction fences.
Changed required quantity or rule revision returns409 `STALE_REVISION`; callers
must recompute and obtain fresh confirmation, not blindly retry the old quantity.
The existing unique pending index and durable window identity prevent parallel
nodes or new idempotency keys from creating a second pending request. Resolved or
explicitly cancelled windows retain history and permit a later request.

Receiving binding requires an accepted pending request, a matching received
receipt line, exact SKU/unit/quantity and current source-location authority. A line
can bind once. Existing and future putaway must use the requested location. This
does not perform putaway, waive inspection, change title or copy cost data.

## Scheduling and Concurrency

The scheduler follows `ftth.scheduling.enabled` and runs every five minutes by
default (`ftth.warehouse.replenishment-delay`). It processes at most25 rules per
tenant transaction using a durable round-robin cursor. Concurrent nodes serialize
through the current-authority fence; the pending index remains the database
deduplication guard. It only recomputes suggestions, never accepts them.

Mutations acquire cutover, exclusive current-authority, topology and owned-row
locks in that order. This planning path does not increment the authority epoch.
Database lock waits are bounded to2seconds and statements to20seconds, inside a
30second application transaction. Contention returns409, not an empty success.

## Migration and Verification

Forward migrations are `V175_115__warehouse_replenishment.sql` and
`V175_115_1__warehouse_replenishment_snapshot_binding.sql`. Existing rule/request
rows are preserved without fabricating acceptance or historical snapshots.

Run `scripts/warehouse/qa.sh server --tests '*WarehouseReplenishmentIT*'
--rerun-tasks --no-parallel` only within the Wave5 host lock and owned environment
lifecycle. The suite covers numeric rounding, reservations, partial generic
inbound, receipt binding, stale acceptance, role/scope revocation, concurrent
acceptance, actual connection termination, lock contention and forward upgrade.
The live seed class prepares a private local fixture manifest for packaged HTTP
verification; it is not a production endpoint or stock-seeding mechanism.
