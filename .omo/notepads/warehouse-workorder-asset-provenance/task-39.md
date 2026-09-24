## Task39 review/rework verified — continue40, finish39 links with40/41

WO Material now integrates planning/templates and request/stock links, exact measured
use and immutable positive correction, explicit NONE declaration, scoped usage history,
name-resolved paged obligations and independent technical/QA/provisioning/material states.
The frozen QA review reads actual persisted fulfillment usage/version, gated by current
WO + actor/location authority; no raw source payload/session/cost exposure. Positive
rework form captures prior plan/use/evidence revisions, appends only extra quantities
and preserves inherited history. Field-only technicians can see the existing evidence
section using the controller's established field permission. Existing completion/QA
commands remain canonical, no invented revision or serial-consumption fallback.

Backend8/3suites PASS2m20s, targeted web29/6 PASS8.76s, full web269/50 PASS51.31s.
Initial review decoder TypeScript errors fixed with default path parameters; final7
review/API tests PASS, TypeScript/build PASS. Whole warehouse+WO oxlint exits0 with4
pre-existing set-state-in-effect warnings in old WO components (new material files clean).
Proof task39/material-review-verification.json; raw reports/logs local ignored runtime.
All QA services cleaned, volumes retained. No migration.148 next free,177/178 reserved43.

Task39 checkbox deliberately OPEN for final cross-navigation to Material Saya40 and
serialized customer-asset workflow41. Next40: actual own pending handovers, acknowledged
stock and returns even after reassignment, scanner/camera fallback and offline draft
semantics. Current workbench custody is per active authorized WO; do not bypass its
roster check to serve former-assignee returns. Add separate own-custody read service
using inventory records/current fences + WorkOrderMaterialContextApi.lockForCustody.
Canonical residual return/ack/handover commands already exist; reuse them. Current issue
ACK requires current WO revision equals dispatch snapshot revision; present mismatch
rather than inventing/replacing revisions. Tasks40–48/F1–F4 open; long-run goal ACTIVE.

## Task39 WO Material UI checkpoint — core flow verified; remaining review/rework ongoing

22web tests/4files PASS3.92s, TypeScript and targeted oxlint PASS. Integrated existing
WO detail with named plan, canonical planning/editor/request links, exact measured use,
positive immutable correction, explicit NONE declaration, paged named usage history,
independent technical/QA/provisioning/material states and actual-revision closure.
Captured body/key survives uncertain replay; stale409 reloads. Current roster/status
hides invalid use, and residual obligations remain visible after cancel/reassign.
No invented allocations or serial consumption bypass. Backend e139250a five tests PASS.

Current additional backend edits for named paged obligation rows and frozen QA review
are being verified by .omo/runtime/material-review-server.sh/log (8tests expected).
Next finish those UI reads and positive rework plan form, add targeted tests, then
full web/build gate before marking39 complete. Task39 remains OPEN;40–48/F1–F4 remain.
No migrations. Goal ACTIVE. Existing evidence anchor remains in WO proof/evidence flow.

## Task39 material workbench backend verified — frontend in progress

Added InventoryMaterialWorkbenchApi with current WO plan/use revisions, bounded own
custody choices and immutable named usage history. Custody matches actual accepted
receipt and use/return/handover descendants against current own ISSUED physical balance;
current location/area topology filters run before totals/pages. No cost/raw posting
DTOs, no broad IAM requests, no writes on GET. Context contains latest usage identity,
not another actor's raw source lines. Canonical report-use/correct-use remain unchanged.

5tests/3suites PASS3m22s: new MaterialWorkbenchIT2, existing UsageLifecycle2 and
LifecycleDelta1. Proof task39/material-workbench-verification.json. QA cleanup done.
Current uncommitted web work: materialExecution API, WO Material section with planning,
usage/positive correction form, history and independent close; tsc pending. Remaining39:
meaningful API/UI tests, named obligation references, frozen QA review, rework form,
all required rejection/reassign/cancel/no-material cases, final gate. Task40 own-custody
page/ack/return/scanner/offline follows; then41–48/F1–F4. Long-run goal ACTIVE.
Task38 COMPLETE at5ed6df25; fullweb249/44, build/lint green before39 additions.
No migrations. Never claim whole39 finished from this backend checkpoint.

