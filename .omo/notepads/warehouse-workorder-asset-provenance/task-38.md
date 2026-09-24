## Task38 replenishment verified; overview next

Replenishment backend d874d2d0:12tests/4suites PASS2m13s, including new2 workbench
and old3 basic/4 commands/3 inbound.16web/3files PASS4.60s, TypeScript and oxlint PASS.
Initial new UI tests corrected jsdom dialog polyfill/role options/required-label matcher.
Proof task38/replenishment-workbench-verification.json. Owned cleanup done; volumes retained.

New Pengisian Stok route provides named rule/request lists, state/active server filters,
read-only current versus captured quantities, create/edit exact-unit rules, bounded
history, recompute, accept/cancel/archive with captured revisions/replay, and binding
an actual receipt line/current revision. Cost/stock/ordinary master read permissions
are not prerequisites for reading or editing an existing rule. Creating a rule uses
named SKU/location pickers. Accepted requests block archive until cancellation.
Receipt binding follows existing backend singular-line exact-quantity contract; it
requires one matching received line (serial multi-line batch binding is not added).

Task38 overview shortages/transit/pending drilldowns and remaining settings history/
delegation next. Reports and policy settings already verified. Then39–48/F1–F4.
Formal long-run goal ACTIVE. No migrations;148 next free. Branch checkpoints pushed.

## Replenishment compile correction; fresh verification next

9dd7fe96 initial backend build failed in13s: missing imports for inbound MasterKind,
SkuSnapshot and LocationSnapshot. No tests executed; copied old report XML is NOT
replenishment evidence. Imports corrected; fresh archive task38/replenishment-corrected
for same12 tests. Reports7 proof remains valid. Replenishment UI under development.

## Task38 reports verified; replenishment backend checkpoint

Reports7backend/3suites PASS2m1s (compiled c155856b, run bd01230b);18web/3files
PASS4.64s, TypeScript and final oxlint PASS. Proof task38/reports-workbench-verification.json.
All owned QA cleanup completed; volumes retained. Reports route operational.

Replenishment named workbench implemented; verification NEXT. New read API requires
request.view and current warehouse/area, never general SKU/location permission. SQL
filters active rules/pending state before totals/pages. Read-only detail compares live
position/suggestion with captured request; never creates a request on GET. Archived
location/history remains readable within current scope, but ineligible for replenishment.
New2 backend tests plus old3 basic/4 commands/3 inbound scheduled via private
.omo/runtime/replenishment-workbench-server.sh (log replenishment-workbench-server.log).
No migrations. UI replenishment/overview/settings history-delegations pending;38 open.

## Task38 reports UI checkpoint

Reports page now supports all nine scoped report kinds, named SKU/location and WO
filters, bounded server pages, paired date range, exact signed ledger/cost values,
unknown cost totals distinct from zero, CSV all-filter export (server max1000), and
revision-bound receipt/issue/return print preview with fresh authority read before print.
Cost/provenance/report gates run before fetch; report-only users need no master reads.
Blob URLs and temporary print portal cleaned on close/navigation. Existing route wired.
18web tests/3files PASS4.64s (reportAPI4/reportUI6/stockUI8), TypeScript PASS.
Oxlint initial two refresh warnings extracted constants into reportPresentation.ts;
final lint next. No backend changes. Run existing ReportIT4/PrivacyIT2/ExportIT1 next
against owned PostgreSQL, then save portable proof. Whole38 still OPEN:
overview/replenishment/settings history-delegation remain; then39–48/F1–F4.

## Policy UI checkpoint verified; task37 discrepancy browser next

Policy backend8tests/3suites PASS2m17s at2dbab388. Saved-vs-draft policy UI now
supports named location/user/role selection, up to9 operation rules/10 ordered tiers,
exact integer thresholds, diff confirmation and actual revision/key retry. Named
choices require all selected current scopes; no IAM directory reads. Policy UI/API
7tests/2files PASS3.03s, TypeScript and targeted oxlint PASS. Initial TypeScript test
mock took no path argument; corrected before the green rerun. Settings route now
works; WarehouseScopePanel remains under catalog access. Full web gate running;
actual returns.spec.ts discrepancy extension is written but not yet executed.
Task38 overview/replenishment/reports/delegations/history remain. Whole37 and38–48/F1–F4 active.

# Task38 implementation notes

## Policy settings prerequisite for task37 browser

2026-09-25: task38 settings policy work began while37 disposition verification runs.
Existing settings route was unavailable, so a real all-UI discrepancy browser cannot
configure independent policy until this component is shipped. Tasks37 and38 remain open.
New policy/details resolves saved policy users/role/location names. Bounded
policy/approvers accepts unique repeated locationId, USER or ROLE, query/page/size;
requires approval.manage and current scope for every location; only active independent
approvers with all selected warehouse/area permissions are returned. Source actor and
its active delegates are excluded. IAM public authority directory now includes role
names (internal only). No ordinary IAM directory permission required for this picker.
Two tests pending plus old PolicyIT andPolicyITRoleAuthority regressions. Private
wrapper .omo/runtime/policy-workbench-server.sh; log policy-workbench-server.log.
Next UI: typed saved policy, edit rules/tiers/user+role choices, location list, actual
revision capture, diff confirmation, stale reload, independent reviewer guidance.
Use existing WarehouseScopePanel/catalog access for grants. Delegation UI, overview,
replenishment and reports still belong to the remaining task38 work.
No migrations. Do not claim task38 or37 complete yet.
