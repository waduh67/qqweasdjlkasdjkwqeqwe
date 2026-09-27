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
  mobile and dark) pass without page errors or document overflow; no business
  data was submitted.
- Independent reviewer `/root/current_warehouse_quality_review` explicitly approved
  this scoped web release on the completed evidence. Proof:
  `ui-release-verification.json` and `reviews/scoped-ui-release.md`.

The original broad warehouse verification remains **FAILED** and is preserved in
`verification.json`; this scoped UI release does not declare F1–F4 or owner
acceptance complete. The original 48 implementation tasks reached technical
closure at `b38d8eb9`, before the owner requested the UI revision.

## Remaining original-plan verification

The historical cutover browser runner fails in both viewports during its before
phase at `web/e2e/warehouse-legacy/before.spec.ts:14`: exact `Alamat` textbox lookup.
After/restart phases did not run. Diagnose its current-frontend/historical-backend
fixture and field labels before changing it. Failed evidence is retained at
`.omo/runtime/warehouse-legacy-eb215dc66ad8d8a95e61a53fea207a97/`.
Do not report those cases as passed or repeat the completed 621/28 cases without
relevant source changes. Final original-plan F1–F4 and explicit owner acceptance
remain pending. Backend, migration, mobile and protocol inputs are unchanged from
the previously audited baseline; their retained evidence remains applicable.

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
