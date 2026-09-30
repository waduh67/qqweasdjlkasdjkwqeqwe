# Report work-order owner boundary correction

Finding: inventory persistence directly read work_order in WarehouseReportCosts,
WarehouseReportDocuments and WarehouseReportPersistence. C1 and the schema blueprint
require access through owner contracts. The joins enforced correct current area
filtering, but bypassed module ownership; this is a source architecture defect, not
an observed stock or customer-data corruption.

The inventory-owned read port is implemented in the workorder adapter, preserving
static workorder -> inventory direction with no callback/bean cycle. It requires an
existing transaction, uses the current tenant and a UUID array for server-derived
area scope, and locks admitted work-order rows FOR SHARE in UUID order. This keeps
area membership valid throughout the consuming query; mutations use the same WO
root lock. Restricted empty IDs return no orders; unrestricted still filters tenant.
No SQL text, table names or query internals cross the port. The inventory CTE contains
only owner-returned UUIDs. There is no DB migration or stock-writing behavior change.

Only loan/sale/cost reports ask for this WO scope; other report types retain their
location-only behavior. Historical print still permits documents with no WO and
requires current owner scope for linked WOs. Snapshot labels/cost lineage are not
refreshed or repriced. All permissions, location scope and current authority checks
remain before output. The owner read uses a20-second statement/transaction bound.

Validation required: new4case WarehouseReportWorkOrderScopeIT, all existing reports,
ModularityTests, complete current server regression and real browser/image gates.
Current static checks: no direct work_order/customer/onu table read remains in the
inventory application source; diff check, workflow5 and actionlint PASS. Runtime is
pending. Full R7 at40cbd34f continues as preserved comparison evidence.
