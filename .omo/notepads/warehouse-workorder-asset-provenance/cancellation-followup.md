
Follow-up audit for finalization: current report counts still describe the frozen
pending sources; cancellation is a separate receipt, so finalizer must require the
receipt set rather than rewriting source state/counts. Current CONTROL_PLANE allows
legacy accept/claimOrCreate/enqueue to add a new pending checkpoint/outbox after
BEGIN. Closing these obsolete creation paths (preserving terminal replays and
existing reconciliation/ACK) or explicitly accounting same-checkpoint delivery
churn is required before finalization. Current cancellation fails closed if its
checkpoint outboxIds/payload/business/effect set changes; do not call that a usable
recapture flow because the original cutoff batch is immutable. See Fulfillment-
Coordinator.accept, FulfillmentPersistence.claimOrCreate/enqueueOutbox. Existing
WarehouseFulfillmentIT legacy fixture creates ambiguous work after ENFORCED and may
need a proper historical fixture when the write fallback closes. No change made yet.

All-current reservations: initial M01 candidates are only a boot snapshot; BEGIN
must reserve changed/new raw serial/MAC from all current owner source projections,
retain old candidates/claims, and serialize canonical claim creation. M06 also needs
app-insert guards and appropriate legacy raw-identity update reservation so unresolved
historical identities cannot later conflict with admitted stock. Use forward SQL;
177.8 is immutable, next177.9,178 remains reserved for final schema constraints.

Reservation audit nuance: reserve current raw values, not stale canonical_candidate
columns. Keep old candidate rows/claims, but distinguish current active conflicts
from historical candidates when validating an admitted baseline. Existing V177.7
admission scans candidate history and requires a reviewed duplicate for other sources;
if a source changed away before cutoff, that historical candidate may need explicit
current-source filtering in a forward function update. Do not release/reuse retired
identities or pretend ambiguous active installed devices are proven stock.

M06 audit: inventory_serial_tombstone has only old tenant RLS (V144), no current
Kotlin writer and no append-only trigger found. Add immutable history/app-write
closure or narrowly proven retirement ownership; current177.9 raw cutoff guard is
only inventory_serialized_asset + onu. Initial reservation marks current tombstone
claims RETIRED, so their previously claimed values still cannot be reused.

Finalization design audit (not implemented): close new legacy WORK_ORDER checkpoint,
outbox and progress creation after BEGIN unless the actual frozen new fulfillment
protocol exists; preserve existing terminal key replay (claimOrCreate uses INSERT
ON CONFLICT, so its guard must allow an already-existing operation key). Onboarding
MIGRATION fulfillment has no inventory effects and must retain its own service flow.
Freeze legacy business/hash/target/effect fields after cutoff while preserving allowed
retry/lease/ACK state changes. Keep postcutoff unknown outbox creation from making the
immutable batch impossible to finish. Existing @Repository HTTP409 business-drift
test may move its expected rejection earlier when the new DB guard prevents the
unsafe mutation itself; never disable a real guard just to retain a test scenario.

Also freeze new case resolutions once an admission exists. Changing a sealed batch
review after posting would otherwise make finalization irrecoverably stale (new
resolution revisions cannot recreate the original review hash). Preserve read-only
history and private downloads after ENFORCED with history locks where appropriate.
Finalization checks the original admission/effect/cancellation set and actual bound
stock/claims under exclusive cutover. Do not reuse pre-admission review_issues blindly:
admitted assets intentionally disappear from the live LEGACY source view and would
look SOURCE_CHANGED_AFTER_CUTOFF. Approved expiry/policy history is historical; later
policy/revocation should not retroactively undo a committed decision. The finalizer
must still load current actor/source access before idempotent response replay.
