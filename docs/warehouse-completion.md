# Warehouse and UI checkpoint — 27 September 2026

The Azure table, field, button and sidebar revision is live at
https://ftth.karuhundeveloper.com/customers. Current web source:
`0a685bc5deef5e397375b5930b4406464cbe0cf8`, image
`sha256:7923f38708d7d2e9f8252b033e586f4747c2d8ea6c49ff6e6bb5fbbc69915aa3`.
The final product change after the verified `329039f2` controls release is one
`min-width: 0` declaration on shared Fluent Combobox controls. It prevents a
picker from extending over a neighboring mobile reassignment button.

Worktree: `/home/fajar/ftth/ftth-ui-ux`, branch `work/ui-ux-revision`.
Private evidence: `.omo/runtime/ui-ux/warehouse-completion-20260927/`.
Release and guarded rollback: `/opt/ftth/setup-private/ui-20260927-warehouse-0a685bc5/`.
See [controls](ui-ux/CONTROLS.md) and [operations](../deploy/AZURE-FTTH-OPERATIONS.md).

## Verified UI release

- All 621 frontend cases pass across 123 test-result files; lint, TypeScript and
  Vite build pass. Lint retains 108 existing warnings and no errors.
- All 26 warehouse workflow cases pass in Firefox. Main cases ran at `0a685bc5`;
  the final extra scenario ran at test/documentation-only `18dfeaa1`.
- Both visual cases pass: 44 routes and 47 captures per viewport. They ran at
  documentation-only `d69f14b2`. All 28 browser cases have no failed, skipped or
  flaky results. Candidate product/build inputs remained identical throughout.
- Six rollback tests pass. All 14 packaged static files match the tested build.
  Guarded activation verified exact served files, backend health, public FTTH and
  Drive HTTP200, and unchanged identity/state of the other 13 protected containers.
- Five production Firefox captures (customer table, form, expanded location,
  mobile and dark-preference emulation) have no page errors or document overflow;
  no business data was submitted. The emulated-dark capture still shows the app
  in light mode, and the location capture shows its loading state. Actual dark
  workflow and completed coordinate-field coverage comes from the local tests.
- Independent reviewer `/root/current_warehouse_quality_review` explicitly approved
  this scoped web release on the completed evidence. Proof:
  `ui-release-verification.json` and `reviews/scoped-ui-release.md`.

## Completed original-plan verification

The current regression is **PASS** in `verification.json`: 621 frontend cases,
26 warehouse workflow cases, two 44-route visual cases, and six real legacy
cutover cases. The legacy phases each pass both Firefox viewports with zero
failed, skipped or flaky tests. They ran at `1dee9f507c567997fec65c8882c66398ad82cb40`
against the unchanged historical V172 application, then the current application
on the same isolated database, then a restarted current application.

Legacy customer/ONU IDs, records and all applied historical migration checksums
remain intact. Both tenants create and retain a new catalog SKU after cutover;
verified stock stays empty. The runbook preflight passes two valid probes and
rejects all six wrong migration/unit/cutover probes. Current legacy evidence:
`.omo/runtime/warehouse-legacy-1eb76996bae9da154c3d84e60d06b2fe/`.

The earlier `Alamat` timeout was a downstream symptom of the old application's
immediate MapLibre mount throwing when Firefox headless lacked WebGL. Trace and
blank-page screenshot establish this diagnosis. The unchanged selectors and
historical source pass with headed Firefox under Xvfb and real Mesa WebGL2. No
map mock, fixture bypass, timeout increase or historical product edit was used.
The earlier failure remains in `verification-before-headed-legacy.json` and
`.omo/runtime/warehouse-legacy-eb215dc66ad8d8a95e61a53fea207a97/`; the preactivation
scoped-release proof still refers to that exact preserved failed snapshot.

All 48 implementation tasks and C1–C11 contracts were remapped to current sources
and authenticated evidence. Backend, migration, mobile, protocol, backend/Gradle build and CI
inputs remain byte-identical to the audited warehouse baseline. Their retained
full-suite and four-case backend restart evidence remains valid; it is not
reported as a new CI or a fresh execution of the old UI. Native shared-code
compilation does not establish native device runtime delivery. The print test
checks actual printable content at the OS print boundary, not a physical printer.

Do not repeat completed checks without a relevant change. The [review guide](warehouse-review.md)
contains the numeric demo and historical Firefox command. The original plan
requires explicit owner acceptance after final independent F1–F4 approval; final
acceptance remains pending. The original 48-task technical closure at `b38d8eb9`
predates the requested Azure UI revision and does not substitute for that reply.

## Cleanup and recovery

Owned QA processes and containers were stopped; test volumes were retained.
The unrelated `compose-fluent-starter` Gradle process was paused only after the
owner's explicit bounded authorization, then **resumed**. Receipt:
`storage/writer-pause.json`, status `RESUMED`. No process remains intentionally paused.

An isolated development PostgreSQL volume was copied into non-CoW files after
storage stalls on Btrfs. All 3,872 files (195,274,394 bytes) match original content,
mode, ownership and timestamps; volume identity and durability settings remain
unchanged. Original data is retained privately under `/var/lib/ftth-qa-recovery/`.
Proof: `storage/nocow-adjustment.json`. The underlying storage cause is unproven.

Use only isolated QA, never production transaction fixtures. Keep credentials,
raw browser reports and database snapshots out of Git. The serial host QA lease is
`/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock`.
