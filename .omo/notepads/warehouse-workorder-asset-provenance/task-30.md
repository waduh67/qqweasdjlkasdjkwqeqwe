# Task30 — reports and printable documents

## Report custody correction and full11-case validation running

reports-compile-fixed executed7 tests with2 failures,0 errors/skips,2m8s. Unknown
cost, filter guards and3 modularity tests passed. Privacy reached revoked scope but
the test used0 instead of1 as the grant revision; corrected fixture. Physical917500
available/82500 consumed passed, then custody-aging counted the consumed sink under
the former technician. Custody/transit reports now exclude CONSUMED/LOST/DISPOSED.
No ledger or stock quantities were changed to fix the report.

Added strict optional workOrderId filter only for work-order-costs; invalid UUID,
duplicate value and unsupported-filter paths reject. Unknown quantity summary is
bounded by base unit (line pages still carry SKU/WO). Added unknown-stock report
using existing explicit legacy-unverified projection; no inferred units. Historical
print rejects DRAFT versions whose mutable lines are not guaranteed frozen.

Current .omo/runtime/reports-full.sh / .log selects *WarehouseReport*IT plus
*WarehouseReportCsvTest and ModularityTests, expected11. New complete numeric2,
real1002-ledger export1 andCSV1 cases compile; execution result not yet known.
Archive task30/reports-full/xml; private reports-full-database.log. All migration
versions through147 immutable,148 still unused. Task28 COMPLETE with183 green;
task30 OPEN until full acceptance/evidence, later30–48/F1–F4 work remains.

## Report CSV compilation fixed; initial integration running

reports-initial failed compileKotlin before any tests (14s): Jackson JsonNode.map
selected its member overload instead of Kotlin collection mapping. CSV now converts
to a sequence explicitly. reports-compile-fixed is compiled and running the same
WarehouseReportIT4 + ModularityTests3; the3 modularity cases have passed so far.
Do not label initial copied XML green; cleanup after compilation copied stale data.

Added WarehouseReportJourneyIT2 full real1km+10ONU /100m+1ONU /82.5m use /17.5m
accepted return /LOAN or SALE deployment journeys, with actual HTTP-created customer,
mixed IDR/USD HALF_UP source costs and replay checks. Added WarehouseReportExportIT1
real334-device receive/putaway ->1002 visible ledger legs (oversized CSV rejection),
and WarehouseReportCsvTest1 formula/control/quoting cases. These4 new cases are NOT
selected by the running initial suite and have NOT compiled/run yet. Next selector
must include *WarehouseReport*IT and *WarehouseReportCsvTest (expected11 total with
modularity). Still need task30 acceptance review, WO filter/summary bounds, report
scope and any SQL/fixture failures from execution. All148+ migrations unused.

## Task30 report foundation checkpoint — NOT yet verified

Task28 existing-flow regression still runs against c09c6da9 in
.omo/runtime/asset-loss-regression.sh / .log (173 PASSED, no failures at last
observation; NOT a final result). Task28 remains unchecked. All migrations through
147 applied/immutable;148 next unused. No new migrations in this checkpoint.

Task30 now has WarehouseReportService/controller/persistence, shared scoped
stock, ledger stock card with pre-range opening, movement/serial chain, continuous
current-dimension custody age/transit, assignment loan/sold status, operational WO
use costs, historical receipt/issue/return print DTOs and bounded CSV. Costs keep
original receipt numerator/basis and exact integer HALF_UP per line; per-currency
totals and unknown quantities remain separate. Handover/removal/loss does not charge
the original installation again. Report.view is independent of item.view; cost.view
is required for WO costs and gates print costs. All outputs omit canonical payloads,
customer labels, evidence/object keys and authority/session data. Scoped locations
precede counts; stock card opening is calculated before dates. CSV max1000, rejects
page/size and oversize rather than silently truncating, neutralizes formulas.

Four WarehouseReportIT tests authored: real82500 use/17500 inspected return with
917500 available and historical print, unknown cost, current scopes/privacy and
filter/export boundaries. NOT executed yet. .omo/runtime/reports-initial.sh / .log
is queued behind the existing QA lock; selects WarehouseReportIT + ModularityTests,
expected7. Archive task30/reports-initial/xml; private reports-initial-database.log.
Do not trust any old XML copied by cleanup after a compile failure.

Task30 still requires actual full1km+10 ONU fixture/1ONU installation, mixed
currencies/rounding, hard oversized-export proof, nonempty loan/sold/aging/transit
coverage, revocation/history review and fixes from initial run. Print names use
actual captured SKU snapshots (receipt/issue/origin); missing old snapshots remain
NOT_CAPTURED, never reconstructed from mutable master. Review bounded response
summary growth and exact historical print scope. Full-plan goal stays active:
30–48 and F1–F4 remain; no main merge/deploy/reset. Continue committing and pushing
coherent tested fixes and portable sanitized evidence. Never commit private runtime.


Task28 active-loan loss has15 green tests against c09c6da9; its affected existing-flow
regression is still running in .omo/runtime/asset-loss-regression.sh / .log. Product
main currently matches c09c6da9. No task30 endpoint/test/migration has been created.
Task28 remains unchecked until that regression and acceptance close; whole-plan goal
remains active. See latest handoff/task-28 notes first.

Task30 acceptance from plan: stock card, serial chain, custody aging, transit backlog,
loan/sold assets, known+unknown WO costs, movement documents, bounded/formula-safe CSV,
receipt/issue/return print payload with source version and stable IDs/names. Costs
require inventory.cost.view separately from inventory.report.view. Numeric reference
is1,000.000m +10 ONUs received;100.000m +1 ONU issued;82.500m used +1 installed;
17.500m inspected return ->917.500m +9 available,82.500m consumed,zero field cable.
Use full actual posting fixture; never seed stock directly. C11 requires exact source
numerator/base-unit denominator, HALF_UP only at report line, separate currency totals,
and explicitly unknown values; no assumed zero or FX.

Useful existing infrastructure:
- WarehouseQueryService has current-authority/location/area/site scope and cost gates;
  report paths need report.view independently, rather than requiring item.view.
- WarehouseQuerySql prefixes scoped recursive location ancestry, bound parameters,
  normalized serial lookup, and filtered positions; internal page() supports safe
  scoped pagination. WarehouseQueryFilter.parse bounds size<=100, range<=366days,
  whitelists keys/order. Constructor itself does not validate, so exports must enforce
  their own hard bound before copying a larger size.
- WarehouseAssetQueries, WarehouseLotQueries, WarehouseQueryPersistence and
  WarehouseTimelineSql provide existing source/timeline logic. queryCost emits known/
  unknown source numerator/basis/currency only when allowed; queryTime emits UTC ISO.
- Existing queryOrigin gates origin-location access and exposes snapshot customer
  labels; reports should intentionally omit personal/evidence/auth fields rather
  than accidentally forwarding private labels, storage keys or operation payloads.
- inventory_document.source_document_id is a real document FK. Handover ID differs
  from acceptance.operation_id; this was already corrected for loan loss.
- ReceiptIntake stores immutable supplier/location/SKU snapshots. Derive historical
  print SKU labels through actual origin line -> receipt intake, not mutable master
  prices/names. Inspect issue/return snapshots for their stable print labels.
- CustomerAssetLoss effect is a new approved closure linked to the original loan
  obligation; customer_asset_loss preserves episode/telemetry. Do not claim a loss
  is a physical removal or blindly use the old IN_RECOVERY-only view for loan reports.

Suggested implementation in inventory module: WarehouseReportService + controller
under /api/v1/warehouse/reports; read-only persistence using existing query-scope SQL;
separate cost permission and bounded export with CSV neutralization/escaping. Follow
existing JSON query response architecture; do not add inverse module dependencies.
WO costs should identify exactly which applied usage postings are counted once,
retain original receipt cost source and leave unknown quantities visible. State any
exception-cost classification explicitly, avoiding double charging a deployed loan
again when later lost. Source reports/exports must exclude hidden records before
pagination/counting. Print payload must keep requested document revision and stable
source identifiers; avoid exposing full internal canonical operation JSON.

Fixture helpers: MaterialLifecycleFixture -> MaterialUsageFixture ->
MaterialReceiptFixture -> WarehouseIssueFixture -> MaterialWorkflowFixture. pickBody
supports every open allocation. receiveStock uses optional stockReceiptCost hook.
MaterialReceiptFixture serial path is intentionally hardcoded2 issued pieces, so
simply passing10 serial strings will FAIL quantity validation. Build the full numeric
report fixture with actual receipts/planning/issue/acknowledgement/usage/installation/
residual acknowledgement and accepted inspection. Existing CustomerAsset* fixtures
show actual install calls; use HTTP customer creation for the full report fixture.

Do not mark task30 started/complete from this preparation note. No148+ SQL needed
unless an actual read/report snapshot requirement warrants it; reserve first.
