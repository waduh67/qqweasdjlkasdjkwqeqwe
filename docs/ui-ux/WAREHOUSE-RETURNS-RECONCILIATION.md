# Retur & Servis and legacy warehouse reconciliation

Work branch: `work/azure-returns-reconciliation-20261001`, based on `main` at `2ce46720`.

## User references

- Azure Monitor Alerts: https://gcdnb.pbrd.co/images/44VbEF_uOAcJ.png
- Azure Migrate Discover setup: https://gcdnb.pbrd.co/images/C3P7vzlI_YNA.png

Use compact Fluent commands, immediate supported filters, plain single-line tables, retained detail panels, and numbered setup steps. No additional frontend dependency or backend/schema change.

## Behavior

- Retur has separate Daftar retur / Menunggu penerimaan tabs. Visited lists remain mounted so filter/page state survives switching tabs and closing details.
- Pending material reception is a paginated table. A document opens a detail panel; Terima material opens a nested review form. Reception sends the existing exact revision and evidence command. On success a Catat retur action resolves the authoritative source by document ID through the return-source API, then opens the existing reviewed return intake.
- Reception and return intake remain separate real transactions. Acknowledgement failures do not show the successful next-step action. Offline/read-only reception remains disabled.
- Successful return creation and detail mutations invalidate the retained list without resetting filters/page. Normal cancellation does not refetch the list.
- Legacy reconciliation uses compact snapshot totals, source filter, retained case panels, and an opening/activation tab. Setup follows review → independent approval → activation. A saved opening and its source cases open as nested panels.
- Case evidence uses a table and keeps selection across server pages. Discarding a decision unmounts its draft and captured command. Discarding finalization clears its draft and command.
- Hashes, epochs, revisions, explicit zero baseline, source-derived quantities and units, independent approvals, uncertain-result retry locks, and immutable source history remain enforced.
- Pending reception API only supports pagination; legacy case directory only supports pagination and source type. No client-only search is presented as a global search.

## Recovery / validation

Current progress: implementation complete; build, frontend regression and Firefox visual/workflow verification in progress. Separate read-only source audit found and verified fixes for list invalidation and discarded-command retention; no remaining P1/P2 in that scope.

Runtime artifacts: `.omo/runtime/returns-reconciliation-20261001/`. Browser spec: `web/e2e/warehouse-workspaces.spec.ts`. Local Firefox configuration is in `web/node_modules/.cache/warehouse-workspaces.config.mjs` (preview port 14190).

Before release, complete frontend tests/build/lint, inspect the non-empty desktop/mobile screenshots, then record exact results and deployed commit here. Do not restore the full warehouse deploy suite removed intentionally in main PR #45, or overwrite the map basemap switcher from PR #44.
