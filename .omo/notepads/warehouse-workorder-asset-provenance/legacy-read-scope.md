# Legacy HTTP visibility and reservation correction (F2)

Independent F2 preliminary rejected e178: retained HTTP readers bypassed warehouse/
area scope, and reservations depended on obsolete serialized status RESERVED.
WarehouseLegacyQueries now reuses WarehouseQuerySql visible_locations, entered by
WarehouseQueryService current-authority fence and each route's own permission.
Both legacy HTTP controllers use these projections. Internal InventoryApi owner
lookups stay separate; dead tenant-only HTTP service readers were removed. Legacy
DTO property names, physical asset IDs and serialized integer units remain.

Reservations derive from OPEN canonical EA serialized allocations on their current
location/custodian, unpicked or picked positive. Dispatch/release no longer appear;
MM/lot/bulk cannot become device counts. All retained read responses are no-store.

New real HTTP tests: WarehouseLegacyReadScopeIT (2) covers all routes, warehouse vs
area restrictions, empty scope, same-session revocation/restoration/disable,
foreign/missing404, independent location/custody permission families, DTO shape and
no physical mutations. WarehouseLegacyReservationIT (3) covers reserve/pick/unpick/
release/dispatch/replay and measured stock exclusion. Actual executed count is
pending; these are5 expected cases. Reviewer caught and root fixed a BIN-destination
fixture issue before commit. Independent source recheck found both findings fixed;
compilation/runtime/full-current proof remain pending. Workflow now runs34focused
classes before unfiltered server regression. Workflow5+actionlint pass.

Complete prior CI2c1 is now actually revalidated:3783modern/609suites,241focused,
7historical+1projection,375sourcehashes,15jobs including acceptancePASS. Safe proof
is task46/ci-2c1d8e08-complete-server-verification.json. It proves only2c1, not this
patch. LocalR7 at40 remains executing/frozen. No simultaneous local JVM was started.

Current F4 preliminary independently verified e178 Docker tar/config/JAR364migration
bytes and all nonserver raw evidence. Preliminary only; findings/SQL/fullgates remain.
Safe notes/evidence are committed for the user's explicit recoverability request;
private raw/env/auth/XML/traces remain excluded from this PUBLIC repository. This
continues the approved checkpoint workflow, not a permission to expose runtime data.

Next push this product checkpoint to NEW work/warehouse-legacy-read-scope for current
CI while existing full runs finish. Preserve active jobs and archives. F1 operational
draft update/expiry gaps remain next; do not mark tasks46–48 or final F1–F4 complete.

## CI compile correction

CI36148238334 at d8a4ae29 failed compileTestKotlin before any focused/full test.
Jackson3 JsonNode.map resolves to a JSON-node mapping API, not Kotlin List.map;
AssertJ therefore selected an object assertion with no containsExactly methods.
Use asSequence().map(...).toList() explicitly at the five collection assertions
and the role-ID request construction. Assertions/expected scope behavior are
unchanged. Raw private compile log original .omo/runtime/ci-36148238334-server-job.log.
No runtime or full pass is claimed for d8a4. Nonserver jobs may still be completing;
preserve them. Subsequent compile/runtime verification is required.

Separate UNCOMMITTED F1 transfer draft update work exists in validation (including
new unapplied V178.10), not part of this compile-only correction. Do not stage it
without completing its UI/tests/migration manifest. Original R7 stays frozen at40.
