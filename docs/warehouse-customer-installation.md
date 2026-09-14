# Warehouse-authorized customer installation

New customer equipment requires a real serialized asset received, issued for the
customer's PSB work order, and acknowledged into the current technician's custody.
A serial number is descriptive data, never installation authority.

## Entry points

- `POST /api/work-orders/{id}/assets/authorize`, with `Idempotency-Key`, accepts
  `expectedRevision` (current WO revision), `assetId`, `issueLineId`, `purpose=INSTALL`
  and optional `ownershipMode=LOAN|SALE`. Tenant, actor, custody and source authority
  are derived server-side. Unknown request fields are rejected.
- `POST /api/customers/{id}/assets/install`, with `Idempotency-Key`, accepts
  `authorizationId`, `expectedRevision=0`, and optional `topology` containing
  `odpId`, `portNumber`, and `installRxPowerDbm`.
- The existing `POST /api/customers/{id}/onus` accepts that install request under
  `deployment`, with the idempotency key in the header. Raw serial-only registration
  returns `409 USE_WORKORDER_ASSET_WORKFLOW`.
- `CustomerApi.provisionOnu` accepts `AuthorizedOnuInstallation`; new registrations
  without it fail at the same customer owner gate. Existing legacy reads and
  same-customer legacy topology operations are unchanged.
- `CustomerAssetWorkflowService` orchestrates public inventory/customer APIs.
  Future discovery callers use `CustomerAssetApi.installDiscoveredAsset`, which
  requires existing authorization and never creates physical assets from serials.
- The old inventory `installed-onu` write returns
  `409 USE_WORKORDER_ASSET_WORKFLOW`; it does not forward to fulfillment.

## Authority and transactions

Both mint and consume reload current IAM authority. The actor must be an active
technician assigned to the active PSB WO, with `workorder.order.field`,
`customer.onu.assign`, current area access and technician-location scope.

Lock order is tenant cutover, current-authority fence, authoritative WO row,
authorization/operation identity, inventory source documents and physical stock,
then customer mutation. The routing preview performs no stock validation or
locking. After WO validation and source locking, the task19 validated SQL reader
is mandatory before inventory mutation. This avoids acquiring shared stock locks
before the WO lock when two consumes race.

Bindings retain actor/customer/WO/asset/issue identity, operation ID, ownership
intent, cutover and authority epochs, WO/plan/use/asset revisions, and distinct
current issue and immutable acknowledgement revisions. Reassignment, cancellation,
scope revocation and stale revisions fail before committed installation writes.

One local Spring transaction posts `DEPLOY` from acknowledged technician custody
to `CUSTOMER_INSTALLED` customer custody, creates the inventory assignment,
consumes the authorization and inserts the customer installation receipt/ONU.
Any owner failure rolls back all of these. Deferred database guards reconcile
their tenant, identity, source, posting, result and position bindings at commit.
The posting emits the existing durable warehouse outbox event; no external
device or BNG call is made while these locks are held.

Same-key, same-payload replay returns the original durable result after current
authority and WO checks. Changed actor, payload or consume key is rejected.
Authorization revision zero is the immutable input revision; consumed revision
one is not a second spend. Mint replay retains its original authorization reference.

## Equipment and boundaries

SKU categories `ONU` and `ONT` create ONU episodes. Other serialized equipment,
including routers, receives the same physical assignment and customer receipt
without fabricating an ONU or accepting ONU topology.

LOAN is the default. SALE records intent only. Legal title remains ISP and no
handover, invoice, payment, swap, removal, return or telemetry-attribution workflow
is enabled here. Task20 remains subject to independent verification.

Forward migrations are V175.59 through V175.63. Task19 authorization row/history
shapes and all previously applied migration bytes remain unchanged.
