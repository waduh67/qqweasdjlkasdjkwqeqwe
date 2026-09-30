# Warehouse reports and transaction slips

Reports are read-only under `/api/v1/warehouse/reports`. They require
`inventory.report.view`, independently of stock browsing permission. The current
tenant, warehouse, area and site scopes apply before pagination and totals.
Responses use `Cache-Control: no-store`; a disabled user cannot reuse an old session
to retrieve a report. Quantities and money are decimal strings, not floating point.

| GET path | Content |
| --- | --- |
| `/stock` | Verified quantities, reservation buckets and issue-eligible availability per SKU |
| `/unknown-stock` | Explicit unresolved legacy quantities, without guessed units or availability |
| `/stock-card` | Posted movement legs with opening and closing base quantities per SKU/location |
| `/movements` | Immutable posting/document/revision/line references and physical dimensions |
| `/serial-chain/{assetId}` | Visible ledger history for one verified, currently accessible physical asset |
| `/custody-aging` | Positive stock held by technicians, vehicles or customers, excluding consumed/lost/disposed stock |
| `/transit-backlog` | Positive transit stock, including recovered stock awaiting intake |
| `/loan-assets` and `/sold-assets` | Assignment, handover and title state, including real recovery or approved loss |
| `/work-order-costs` | Original receipt cost basis for posted use and deployment; requires `inventory.cost.view` |
| `/documents/{documentId}/revisions/{revision}/print` | Safe historical receipt, issue or return slip |

List responses contain `items`, `page`, `size`, and `totalElements`. Filters are
`skuId`, normalized `serial`, `locationId`, `status`, `condition`, `owner`, paired
`from`/`until`, `page`, `size`, `sort`, and `direction`. `workOrderId` is additionally
accepted on `work-order-costs`. Unknown/repeated filters, invalid IDs and invalid
units/statuses reject with 400. Page size is 1–100. Date ranges are UTC instants,
half-open `[from, until)`, at most 366 days. The consuming UI displays local dates.
History reports support `createdAt` or `id` sort; direction is `asc` or `desc`.

The stock card calculates its opening from earlier authorized ledger entries
before applying the date range. Optional status/owner/condition filters select
the balance bucket being tracked. Each row is one leg, so an internal status
change can have two rows at the same location. Receipt boundary OUT legs are not
physical stock. Custody age starts at the latest zero-to-positive entry for that
exact current piece and dimension; partial incoming stock does not restart it.
Splitting a piece establishes a new dimension entry for the child piece. Ages are
elapsed seconds at query time, and are not measures of service age or SLA breach.

WO costs are an operational use report, not an accounting valuation ledger.
They count CONSUME/DEPLOY legs at consumed/installed destinations and their
explicit linked compensations. Handover, removal, return and approved loss do not
charge a prior installation again. Reusing equipment on another WO is a distinct
operational deployment. This report creates no invoice, payment, FX conversion or
general ledger entry.

Each cost line retains `sourceTotalMinor`, `sourceBasisQuantityBase`, signed
`quantityBase`, currency, and `lineTotalMinor`. It applies exact rational HALF_UP
rounding once, at the report line, then sums rounded lines within each currency.
For example, `1000006 × 82500 / 1000000` rounds to `82500` minor IDR, while
`1999995 × 1 / 10` rounds to `200000` minor USD. Those totals stay separate.
Missing or inaccessible original receipt cost produces `costState=UNKNOWN`, a
null line amount and a separately labelled unknown quantity, never a zero price.
`currencyTotals` and `unknownQuantities` cover all matches before pagination;
unknown totals are grouped only by base unit to keep that summary bounded. Use
`workOrderId` to obtain a WO summary. Totals explicitly describe `VISIBLE_LOCATIONS`
and may therefore be a scoped subset of the work order.

Print payloads contain the exact requested document revision and operation time,
stable document/line/asset IDs, quantity and captured SKU names. Receipt and issue
names come from their immutable snapshots; returned stock retains its original
receipt name when available. A historical name that was never captured is labelled
`NOT_CAPTURED`, never replaced by today's master name. Mutable draft revisions are
not printable. The caller must be able to see the document's source/destination
locations and posted movements through that revision. Costs are entirely omitted
without current cost permission. Internal canonical payloads, customer addresses,
customer labels, evidence storage references and authentication metadata are never
part of a report or slip.

Append `/export.csv` to any named list report to export the same authorized public
rows. Exports reject `page`/`size` and more than 1,000 matching rows; narrow the
filters instead. Headers are public DTO paths, fields are quoted and embedded
quotes escaped. Formula prefixes, including after whitespace/control characters,
are prefixed with an apostrophe. CSV export does not expose an internal SQL row or
stored command response. Serial detail and print endpoints are not CSV list routes.
