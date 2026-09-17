# Customer And Material Read Compatibility

Task24 preserves existing customer/service/import operations without making serial
lookup or imported customer records a physical assignment authority.

## Imports And New Assignments

- Express PSB creates the customer, pending subscription/access and PSB work order.
  It does not create an ONU, serialized asset, stock movement or assignment.
- Customer imports retain per-row results. ALREADY_INSTALLED records a migration
  fulfillment request; it does not invent an installed device or warehouse origin.
- Multipart CSV staging, commit identity and promotion remain separate. A staged
  or PROCESSING response is not proof that customers were created. Inspect the
  result and per-row failures after promotion.
- Background promotion uses the server-installed tenant context; HTTP permissions,
  row-level security and the system-actor audit convention remain unchanged.
- New ONU registration still requires an issued deployment authorization. Raw
  serial and the old inventory installed-onu route return
  `409 USE_WORKORDER_ASSET_WORKFLOW`. A same-customer existing-device lookup does
  not create an assignment or prove provenance.

## Additive ONU Reads

Existing `OnuView` fields retain their meaning. Four fields are additive:
`assetId`, `assignmentId`, `provenance` and `retiredAt`. The customer owner projects
its persisted episode fields in one batch. Legacy devices report `UNKNOWN` and
nullable historical references; the read path never fabricates receipt history.

## Material History Versions

`MaterialConsumptionApi.forCustomer` remains deprecated and returns preserved
integer-count facts. Its `CustomerMaterialFactRef.quantity` is still an `Int`.
Neither measured cable use nor a deployment that never emitted an old material
fact is synthesized into this legacy list.

`MaterialConsumptionApiV2.forCustomer` is the inventory-owned, tenant-scoped read
of verified measured facts and posted deployment assignments. Deployment fact IDs
are their immutable assignment IDs; measured fact IDs are the original fact IDs.
SKU, stock identity, posting ID, use revision, compensation reference and recorded
time come from the owning persistence records, not customer or work-order tables.
Unknown legacy units are excluded from V2 rather than guessed.

The query uses a single database statement for rows and total, ordered ascending
by recorded time then fact ID. Pages default to25, allow1-100 items, and retain the
total on an empty high-offset page. Query failures propagate; they are not empty
successes. `82500` base units with `MM` display as `82.500` with `M`.

Subscriber360 retains the legacy `materialHistory` list and adds an explicit
`materialHistoryV2` page and `access.materialHistoryV2`. Both use the existing
work-order-view facet permission. `materialPage` and `materialSize` select only
the V2 page; they never truncate or reinterpret the legacy list. A denied facet
is null, an authorized empty V2 result is an empty page, and a backend failure
fails the request. Existing client fields and meanings are unchanged.

## Portal Boundary

Portal account, connection, device and session DTOs remain separate allowlisted
types. Operator ONU/material projections are not reused as portal response types.
Warehouse cost, bins, custodians, approval/private evidence and GPS do not cross
this serialization boundary. Equipment-source visibility in the operator API is
not permission to expose inventory internals through the portal.
