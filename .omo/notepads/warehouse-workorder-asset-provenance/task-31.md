# Task31 — shared warehouse web foundation (IN PROGRESS)

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
