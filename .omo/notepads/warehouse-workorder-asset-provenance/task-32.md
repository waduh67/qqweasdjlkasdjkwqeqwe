# Task32 preparation (implementation not started)

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
