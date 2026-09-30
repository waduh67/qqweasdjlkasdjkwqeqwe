# Task31 — shared warehouse web foundation (IN PROGRESS)

## Task31 COMPLETE —49 unit tests and both real browser projects green

setup-mobile-fixed against55b19223: desktop1280x900 and touch/mobile375x812 both
PASSED (2tests,12.35s,zero failures/skips/flaky). Real UI signup, role/user creation,
independent approver login, overview+queue, stock API403 and route denial, unknown
route unavailable, no horizontal document overflow. All warehouse requests were
real, no mocked responses or SQL inventory seed. API+Vite proxy readiness proved
warehouse_e2e/warehouse_app/owned marker; role NOSUPERUSER NOBYPASSRLS. All339
migrations through147 booted. Server bootJar and web TypeScript/Vite passed.
49 API/control unit tests and14 prior API/session/nav regression tests are separately
recorded. Portable verification.json +reviewed synthetic-account desktop/mobile
screenshots committed under task31. Raw result JSON/auth-bearing traces stay private.

All owned QA processes/containers/network stopped cleanly; volumes retained.
Task31 checkbox now checked; whole goal stays ACTIVE. Next task32 setup/catalog
CRUD and user warehouse scope UI; read task-32.md preparation and actual contracts.
Current other warehouse routes explicitly unavailable pending32–41; do not claim
full operational UI complete. Continue32–48/F1–F4 with commits/remote checkpoints.
No migration changes;148 nextunused,177/178 reservedtask43. No subagents or deployment.

## Task31 desktop passes; mobile drawer selector fixed

setup-label-fixed against9b794f79: real desktop journey PASSED, including signup,
UI-created approval-only role/user, overview+queue, stock API403, stock route denied,
unknown route unavailable and no horizontal page overflow. Mobile reached the same
approver login then failed opening navigation: translated-offscreen drawer still
satisfies Playwright isVisible(). Helper now reads header aria-expanded at<=820px
and uses actual touch tap to open drawer and section. No application code changed.

Next run .omo/runtime/warehouse-setup-browser.sh setup-mobile-fixed. Need BOTH
projects green in one run before marking31complete. Last run1passed/1failed31.4s,
all owned cleanup passed, volumes retained. No migrations changed. Continue32–48/F1–F4.

## Task31 browser reached real UI; required-field selectors corrected

setup-health-fixed failed compilation only (Any? health detail); now requireNotNull
uses previously validated values. setup-health-compile-fixed built successfully,
API+Vite proxy health passed with the owned marker/database/app role, then BOTH real
browser projects reached signup and failed the same test selector: Fluent adds a
required asterisk to label text. Accessible textbox name is correct; exact getByLabel
was too strict. Helpers now match the field label without requiring exact raw text.
No application signup or permission failure has been observed yet. Both failed runs
cleaned owned processes/containers successfully, volumes retained.

Next .omo/runtime/warehouse-setup-browser.sh setup-label-fixed, verify complete
signup/role/user/approver journey. Task31 remains OPEN;49 unit proof remains valid.
Task32 preparation notes saved separately (contracts, explicit area grants, scopes,
UI prerequisites), implementation not yet started. Whole goal ACTIVE.

## Task31 browser readiness correction (not yet verified)

Published a030927f contains49 green unit tests and the new navigation/controls.
setup-initial browser attempt built server/web and booted all339 migrations through
V175_147 in warehouse_e2e, but readiness failed before any browser test: SMTP health
was DOWN although email is intentionally disabled, and no warehouse health indicator
actually existed (earlier handoff assumption was wrong). Added profile-only
WarehouseQaHealthIndicator querying database/current app role/marker and requiring
NOSUPERUSER/NOBYPASSRLS/no role/db creation; mismatches fail DOWN. Disabled only the
unused mail health probe in warehouse-e2e. No production profile behavior changed.

Next run .omo/runtime/warehouse-setup-browser.sh setup-health-fixed with private log.
Check actual compilation, owned API/proxy readiness, then real signup/approver browser
journey; no green browser evidence yet. Keep task31 OPEN until both projects pass.

## Task31 navigation and transaction controls — browser harness pending

Warehouse routes now use independent permissions and a separate Gudang & Logistik
sidebar group. /warehouse and /warehouse/approvals use the current paginated API;
approvers do not need inventory.item.view. /warehouse/stock reads typed quantities.
Old WarehouseOperationsPage is a compatibility export; client-generated approval
hashes are removed from the active/legacy web entry. Other planned routes and
unknown paths explicitly report unavailable until their tasks are implemented.

Shared controls under components/organisms/warehouse: exact quantity field/readout,
manual/keyboard serial lookup (lookup only), named lines, status, timestamp/history,
and captured-command dialog. Network/invalid-success ambiguity locks dismissal and
retries the original command; definite 400/402/403/404/409/422 rejections allow return
or explicit document reload. Query state discards obsolete filter results.
49 unit tests passed (44 API +5 controls); TypeScript passed. Lint passed with
existing repository warnings. Isolated jsdom dialog shim is only in unit tests.
No real browser success claimed yet.

New real browser harness: playwright.warehouse.config.ts, e2e/warehouse/helpers.ts
and setup.spec.ts; actual UI signup, role/user create, login independent approver,
API readiness database/user/marker assertion, denied stock API and route, explicit
unknown route, 375px touch/mobile and desktop. No API response mocks or SQL seeds.
application-warehouse-e2e.yml disables scheduling/demo/radius/SMTP fallback/throttle
and automatic provisioning; existing qa.sh owns backend/database/object store.

NEXT: run .omo/runtime/warehouse-setup-browser.sh setup-initial (private log),
resolve actual failures and capture sanitized portable browser evidence before
checking task31 complete. Wrapper owns outer flock fd8; qa uses fd9; cleanup retains
volumes. Then finish32–48/F1–F4. Goal ACTIVE. No migrations changed;148unused.

## Task31 API foundation —44 tests and TypeScript green

Quantity, runtime codecs, master/stock/lookup DTOs and immutable command transport
now implemented under web/src/api/warehouse.44 tests passed with plain npm test;
`npx tsc -b` passed. Portable evidence task31/api-verification.json. Shared
masters.ts exposes typed list/save/archive commands and stock/identity reads.
Responses validate UUIDs, safe numeric revisions/pages, string quantities, unit/
display consistency, tracking/ownership/states and bounded pages; malformed data
throws WarehouseDataError instead of empty success. Optional nullable fields remain
null and unknown additive fields do not leak through typed views.

command() captures serialized JSON/key once, coalesces concurrent submissions and
reuses both after network loss or401 token refresh. It deliberately reauthorizes
via server on later execute(), never caches a prior successful reply as permission.
Uses api.request and original Idempotency-Key; no client approval hash. Tests prove
input-object edits do not mutate captured retries, conflicts retain keys and bad
success bodies reject. All this is unit evidence, NOT browser acceptance.

Next: shared warehouse route/gate/nav and named controls; replace old inventory
warehouse shell; build real setup.spec.ts desktop/mobile harness and isolated
warehouse-e2e profile through existing qa.sh browser. App.tsx also has a misleading
RequireAnyPermission hardwired to canViewHotspot; do not reuse it blindly for
warehouse. Add a warehouse-specific gate or correctly generalize with regressions.
No backend QA active, migrations unchanged,148unused. Task31/whole goal OPEN.

Tasks1–30 complete. Task30 has53 green reports/receipt cases against422cf536,
portable evidence task30/verification.json, published11468066. No backend QA
currently active; owned containers/processes stopped and volumes retained. All
migrations through147 immutable,148unused (reserve first if needed).

Current31 foundation:
- Read and extended web/DESIGN.md before adding repeated controls. Existing Fluent
  atoms/molecules, flat DataTable, Modal/ConfirmDialog, Badge and page stacks remain.
  Warehouse group, field Material Saya, explicit units/lookup-only scanner,
  preserved request payload/key, permission-specific routes and all states defined.
- web/src/api/warehouse/quantity.ts exact bigint conversions: metre comma/point up
  to3 fractional digits -> checked signed64-bit MM string; whole EA; reject excess
  precision/negative/overflow; allow explicit0 only when requested. Displays can
  retain larger aggregate numbers and signed ledger deltas without Number rounding.
-20 quantity tests green.14 existing API client/session and shell navigation tests
  also green. Portable sanitized evidence task31/quantity-verification.json.
- Node26 native localStorage shadowed jsdom, causing all20 initial cleanup hooks to
  fail. NODE_OPTIONS=--no-experimental-webstorage worked, but passing only a parent
  node CLI flag did NOT propagate to Vitest workers. Final fix is feature-detected
  test.execArgv in web/vite.config.ts; plain npm test now passes. package.json was
  restored to its original scripts; no storage mocks or new dependencies added.

Finish31 next (NOT implemented yet):
1. Runtime DTO decoders matching server contracts (master/quantity/page/status),
   stable mutation transport through api.request retaining original JSON+Idempotency-Key
   on retry. api/client.ts already preserves RequestInit across401 refresh; reuse it.
   No browser hash becomes approval authority. Tests malformed DTO/decimal/retry.
2. Permission-aware warehouse routes/navigation and reusable named line/status/history/
   quantity/serial/dialog controls. Current App.tsx /warehouse incorrectly requires
   inventory.item.view, so approver-only users cannot enter. RequirePermission is
   defined in App.tsx and accepts only a string. Sidebar supports permission arrays.
   Actual nav definition is web/src/components/templates/Layout.tsx GROUPS; remove
   only warehouse from Jaringan, keep network inventory. Existing WarehouseOperationsPage
   calls old /api/inventory endpoints and creates approval hashes; replace that shell
   with real /api/v1/warehouse contracts, not empty successful placeholders. Unknown
   warehouse routes must explicitly fail. Plan tasks32–41 supply full screens.
3. web/playwright.warehouse.config.ts +web/e2e/warehouse/setup.spec.ts with actual
   signup/readiness/approver navigation, desktop and375px mobile, NO warehouse mocks.
   qa.sh browser already owns startup/cleanup: API17880,web14188, warehouse_e2e DB,
   app rolewarehouse_app and verified marker health. Requires missing
   server/src/main/resources/application-warehouse-e2e.yml to isolate external
   adapters; disable scheduling/seed/external notifications/radius appropriately
   with existing properties, retain real warehouse DB and MinIO. Read source before
   choosing properties. No public deployment or real hardware/email action.
   qa.sh validates real health via proxied /actuator and requires nonzero desktop+
   mobile Playwright success. Existing default playright config is unrelated4188
   preview without API proxy. Existing E2E provisioning fixtures mock routes and
   must not be copied as warehouse acceptance proof. New traces/results stay private.

Useful paths: web/src/api/client.ts (api.request/blob,tokenStore,refreshSession),
web/src/auth/useCan.ts (can/canAny/readOnly), web/src/components/organisms/DataTable.tsx,
web/src/components/molecules/ConfirmDialog.tsx, web/src/components/templates/Layout.tsx,
web/src/components/molecules/SidebarNav.tsx,web/src/App.tsx,web/vite.config.ts.
No zod/runtime-schema dependency currently; implement small clear decoders or add
one only if justified. TypeScript ES2023 with noUnused and erasableSyntaxOnly.
Server SKU/Location/Supplier snapshots in WarehouseMasterModels.kt; report DTOs in
WarehouseReport*.kt and docs/warehouse-reports.md. Server canonical serial is
trim+uppercase(Locale.ROOT) in common/domain/identity/DeviceIdentity.kt. API input
UUIDs/revisions/current authority remain server-validated.

Continue whole-plan31–48/F1–F4, saving coherent commits plusremote checkpoints and
handoff notes. Goal remains ACTIVE. No subagents unless separately authorized by
user/applicableAGENTS/skill. No main merge,deploy,reset,rebase or volume deletion.
