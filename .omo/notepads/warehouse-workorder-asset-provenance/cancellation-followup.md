
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
