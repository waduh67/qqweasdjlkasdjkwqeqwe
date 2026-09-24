# Task32 preparation (implementation not started)

## Task32 functional setup green; mobile table and breadcrumb refinement

catalog-area-fixed against beecbc60:4 passed,0 failed/skipped/flaky,59.084s.
All owned cleanup succeeded, volumes retained. Reviewed desktop/mobile screenshots
revealed resource-table mobile header/value alignment and the global catalog crumb
incorrectly saying Paket Internet. Added warehouse-only responsive label/value rows
with accessible column headers retained, full-size row actions, and full-path warehouse
breadcrumbs. Other resource tables retain their existing presentation.
76 targeted tests (61 warehouse +15 DataTable regressions), TypeScript and lint passed.
Browser assertions now cover the actual warehouse breadcrumb and action target size.
NEXT: run catalog-layout-fixed, review both screenshots, then save portable task32
proof and mark32 complete. Task33 contract notes ready; continue33–48/F1–F4.

## Task32 desktop setup passes; mobile area prerequisite layout fixed

catalog-initial against3dc45f23:3passed/1failed59.67s. Both task31 navigation cases
and the full task32 desktop setup journey passed. Mobile setup failed at existing
AreasPage: its unwrapped horizontal create row pushed the Tambah button outside
viewport. Screenshot inspected: code/name inputs extended past375px. Fixed only
that prerequisite form to wrap with flexible12rem/16rem bases and min-width0.
This is a real UI fix, not a forced browser click. All owned cleanup succeeded;
volumes retained.61 unit tests remain green from the preceding checkpoint.

Next .omo/runtime/warehouse-catalog-browser.sh catalog-area-fixed. Need both full
setup journeys green and inspect screenshots before32completion. Continue33–48/F1–F4.
Task33 contract preparation saved in task-33.md; implementation not started.

## Task32 catalog implemented —61 unit tests; browser setup pending

/warehouse/catalog now routes to actual location, SKU, supplier and user-scope tabs.
Setup checklist explains explicit area grants and links existing area/user admin.
Location editor supports kind, same-area warehouse/bin parent, optional named site
and active custodian selection, issue eligibility, read-only/archive and confirmations.
Current reference404 preserves its stored ID with an explicit unavailable-name label;
other failures show error, never fabricated names or a successful empty directory.
No mutation follows merely selecting a parent/user. Named selectors are searchable
and paginated. Master list now uses existing DataTable resource presentation.

Scope panel reads actual grants including revoked revision. It cannot infer zero
when GET fails; only an absent entry in a successfully read list uses expected0.
Grant/revoke review names user/location and explains inherited versus direct access.
409 reloads the current grant without automatic resubmission. Role and area remain
independent authority.61 unit tests passed (57prior+4 location/scope); TS+lint passed.
Portable task32/catalog-verification.json. No task32 browser success claimed yet.

Extended e2e/warehouse/setup.spec.ts to4 real cases total across desktop/mobile:
existing task31 journey + new UI area creation/self-assignment/login; warehouse/bin/
quarantine setup; SKUcable exact82500MM minimum andONU; supplier; edits of all3;
referenced root archive denied; unused SKU archive succeeds/read-only; duplicate code
keeps editable draft; UI role/user/area creation and scoped readonly grant; reader
sees main+child but not standalone quarantine. No SQL seed or API bypass for setup.
New catalog.ts browser helpers use only UI actions and observe actual responses.
helpers.createUser accepts optional area checkbox labels/prefix, original31 unchanged.

NEXT: run .omo/runtime/warehouse-catalog-browser.sh catalog-initial with private log.
Wrapper archives under task32, retains volumes and owns cleanup/locks as before.
Resolve real UI failures, inspect mobile screenshot, capture portable proof and mark32
only after required cases pass. Then33–48/F1–F4. Goal ACTIVE. No migrations added.

## Task32 IN PROGRESS — editor/picker foundation57 unit tests green

Task31 complete and published170d2158 (real browser source55b19223; desktop+mobile2
passed and49unit, portable evidence/screenshots). All owned QA stopped; volumes kept.
Task32 has generic WarehouseMasterPanel (search/page/state, create/edit/read-only,
archive confirmation with real revision), SKU and supplier editors using captured
commands and native form validation; they are NOT yet wired to /warehouse/catalog.
Location editor, user-scope panel, actual catalog/setup checklist and browser setup
extension remain to implement. No task32 browser claim or checkbox completion.

WarehousePicker uses named bounded search/pages and preserves selected references
across searches; no silent first-page truncation. API setup.ts decodes existing IAM
user/site pages, areas and warehouse-scope grants; malformed/missing revision fails.
masters.ts adds typed get-by-id. Error messages preserve archive/business reasons
instead of treating every409 as stale. Pagination moved to shared warehouse control.
Button AppButtonProps now distributes Omit over Fluent's button/anchor union to
preserve native form prop (type-only change; no runtime implementation change).
DESIGN.md picker/scope conventions updated before these controls.

57unit cases passed (prior49 +8new setup/editor/picker cases); fullweb tsc-b and lint
passed. Portable task32/foundation-verification.json. Tests cover actual MM minimum
payload, retained editable supplier draft after409, archived/read-only no save,
actual stale revision+explicit reload, selector preserves chosen name acrosspages,
invalid IAM paging/missing grantrevision, and meaningful archive reason. Extend with
location/scope/archive-page behaviour and actual browser setup before32completion.

Next read task-32.md contract preparation below. New tenant listener really exists:
WarehouseTenantCreatedListener initializes ENFORCED/NEW_EMPTY atomically on tenant
created event (so later receipt UI needs no fake cutover for new tenants).
Continue32–48/F1–F4. No migrations changed;148unused; no active backend/QA sessions.

Finish real task31 browser proof first. Task32 plan is SKU/UoM/tracking/model,
warehouse/bin/supplier CRUD + archive, explicit user warehouse scopes, setup
checklist, meaningful denied/conflict/readonly states. Extend actual setup.spec.ts
for complete empty-tenant setup via UI, never SQL seed. Reuse task31 typed APIs,
exact quantities and captured WarehouseCommandDialog.

Verified backend contracts:
- docs/warehouse-masters.md; WarehouseMasterModels.kt / WarehouseMasterService.kt.
- /api/v1/warehouse/skus requires inventory.sku.view/manage; locations location;
  suppliers receipt (NOT a made-up supplier permission). POST create no revision,
  returns201 bare snapshot; PUT id includes expectedRevision; POST id/archive only
  expectedRevision. No reopen/delete. Revisions begin0. Code[A-Z0-9][A-Z0-9._-]{0,63};
  trimmed name1..200; category<=100/model<=200/contact<=500 optional null rather than
  blank. SERIALEA; minimum base quantity string including0; ownership nonemptyLOAN/SALE.
- list page0,size25(max100),search/code/name,stateACTIVE|ARCHIVED,sortcode/name,
  directionasc/desc. Never silently truncate a picker at100; use search+paging.
- CurrentAuthority treats empty area grants as ZERO warehouse access (IAM screens
  currently say empty=all for other modules; warehouse setup must explain explicitly).
  Create area via /areas then edit own access via /users preserving roleIds; login
  again after self-access change (refresh tokens revoked). profile.areaIds and
  useAuth.refreshProfile available. After own area grant, root warehouse(areaId)
  succeeds and atomically grants creator location scope. BIN needs WAREHOUSE/BIN
  parent, same area and compatible site; chain max31ancestors. TECHNICIAN requires
  active custodian user. issueEligible only warehouse/bin/technician/vehicle.
- Existing stocked/referenced SKU unit/tracking or location hierarchy/kind/area/
  site/custodian/eligibility cannot change. Archive rejects SOURCE_NOT_VERIFIED with
  useful server message; never rewrite/delete references to make it succeed.
- Runtime errors currently generic409; improve mapping to preserve server business
  reasons (archive etc) rather than calling every conflict stale. General data
  integrity duplicate also maps SOURCE_NOT_VERIFIED without leaking foreign detail.
- Existing listStock/lookupIdentity codecs already present; manual scanonly neverstock.

Scope setup:
- WarehousePolicyController base /api/v1/warehouse/settings.
- GET /scopes/{userId} -> array WarehouseScopeGrant {id,userId,locationId,active,revision};
  inventory.location.view. PUT /scopes/{userId}/{locationId} withIdempotency-Key
  {expectedRevision,active}, inventory.location.manage. New grant rev1; initial
  expected0, creator legacy scope can be0; use actual GET rev. Revocation notdelete.
- list checks actor can access EVERY returned location, so partial scope may404;
  do not mislabel404 as no grants or silently submit guessed0 for existing records.
- Scope target must be active directory user. UI may use existing IAM /api/users
  boundedpage/query requiringiam.user.view; if missing permission explain and link
  authorized user admin, never UUID-only editing. For self, user profile has name/id.
- Optional site list in existing network API; sites must match location area, cannot
  be guessed. Keep no-site valid rather than invent site from area.

Existing UI/test helpers:
- /areas has Code/Nama fields and Tambah, real API.
- /users has Pengguna baru Blade, role+area checkboxes. DataTable row action menu
  button aria-label=Aksi baris then menuitem Akses. AccessEditor title Akses—email.
- /roles has Role baru, Nama and matrix checkboxes aria-label exact permission code.
- DataTable named rows and rowActions; sharedTabs Fluent. Native dialog is real only
  in browser; test shim stays unit-only.
- WarehouseRoutes.tsx currently inline overview/basicstock/approvalqueue; catalog
  explicitly unavailable until this task. Add real catalog there; separatepages
  can replace stock/approval/overview at34/37/38. navigation.ts definespermissions.
- No new migrations needed. Next unused148 only if actual backend gap requires it.
