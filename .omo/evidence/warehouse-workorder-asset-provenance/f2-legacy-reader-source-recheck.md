# F2 legacy-reader correction: source recheck

Status: **F2-01 and F2-02 addressed in reviewed source; compilation and runtime gates pending. This is not final F2 approval.**

The original findings in `f2-quality-security-preliminary.md` apply to `e17827188175d51a9281cc56c50b950f4fe9e38f`. This follow-up reviews the uncommitted compatibility correction in the separate validation worktree, based on `a1d4eb19461b0c0adb9bcc54611a65af48362081`. `f2-legacy-reader-source-recheck.json` binds the nine reviewed product/test/documentation/workflow files to their SHA-256 values. Later changes require reconciliation with those hashes.

## F2-01: corrected at source

- `WarehouseQueryService.kt:27`–40 gives every retained compatibility reader a transaction and the relevant permission family. `access()` at line 72 obtains current authority, validates the requested permission, and obtains current warehouse, area and site visibility under the existing authority fence.
- `WarehouseLegacyQueries.kt:10`–44 uses `WarehouseQuerySql`'s canonical `visible_locations`, including tenant, warehouse, area and site/ancestry checks. Empty restricted scopes remain empty. Serialized detail applies the same predicate before selecting its ID, and the common query helper returns `NOT_FOUND` for no row.
- Both HTTP controllers route retained reads to these fenced readers. No HTTP path in this patch still calls the tenant-only internal `InventoryApi` lookup. Unused tenant-only compatibility list methods are removed from `InventoryApiService`; owner API operations remain separate.
- The retained field sets are explicit. The patch adds no cost/provenance payload fields. JSON responses carry `Cache-Control: no-store`; inaccessible and missing serialized assets share the same error path.

I found no new material authorization or locking regression in this correction. The readers use the existing current-authority/site fence order and do not add a posting operation, mutation or new inventory row lock.

## F2-02: corrected at source

`WarehouseLegacyQueries.kt:27`–37 now derives the legacy device reservation DTO from an open, positive durable reservation for the same serialized stock identity, location and custodian. `EA` plus a null lot and the serialized asset join exclude measured cable and bulk lots. The physical asset can remain `AVAILABLE`; picked reservations remain visible, while released/consumed rows cease to match. `EXISTS` preserves one device row rather than duplicating the device when querying allocations. No quantity conversion or physical status mutation is introduced.

The new query and documentation match the intended canonical-reservation semantics. The old `status == RESERVED` filter is removed.

## Test source review

`WarehouseLegacyReadScopeIT` defines two real HTTP test cases. Its first case uses real receipt/putaway/reservation fixtures, then checks empty warehouse scope, two-location visibility, area removal, scope removal/restoration using the same session, inaccessible/missing and foreign-tenant serialized detail, user disable, preserved field sets, no extra cost/evidence payload and unchanged stock/reservation row counts. The second checks each legacy permission family independently and anonymous rejection.

One concrete fixture error was found and sent to the coordinator during review: the initial hidden destination was a `WAREHOUSE`, while `ReceiptDispositionPlanning` requires an issue-eligible `BIN`. The reviewed correction at test lines 22–27 creates a warehouse parent and an explicit same-area issue-eligible bin child. The receipt's putaway now targets that bin. No remaining material fixture issue was found by source inspection.

`WarehouseLegacyReservationIT` defines three executed cases after parameter expansion: release after pick/unpick, dispatch with replay, and measured cable exclusion. The first two assert the exact retained DTO fields, physical `AVAILABLE` status and matching custodian, a read with no count changes, visibility after pick and unpick, and absence after release or dispatch. The release command's plan revision `1` matches the inherited fixture and `InventoryMaterialService` validation. The cable case confirms a real open picked `MM` allocation while the legacy device array stays empty.

These five cases have not been run by this reviewer. The workflow change adds both new test classes and existing query privacy/compatibility classes to the focused stage. `git diff --check` completed successfully for the current patch. That static check does not establish compilation or executed behavior.

## Evidence still required

1. Successful compilation and actual nonzero executed results for both new test classes (five cases total), plus relevant compatibility/privacy cases, on the committed correction.
2. Complete successful current-source full-server results and process completion, including nonzero `ModularityTests`, with source identity reconciled to the committed patch. The coordinator's verified complete older `2c1d8e08` result is historical evidence; it does not validate these new sources.
3. The other outstanding final F2 gates in `f2-quality-security-preliminary.md`, including relevant web/KMP, upgrade/non-owner, browser and GPON documentation/offline evidence. Physical GPON certification remains explicitly deferred by the user; native compilation alone is not release evidence.

This reviewer performed source and read-only Git/static inspection only. No Java/Gradle/Docker/browser/database QA, product edits, index mutation, commit, push, migration change or deployment was performed. No private runtime payloads are included here.
