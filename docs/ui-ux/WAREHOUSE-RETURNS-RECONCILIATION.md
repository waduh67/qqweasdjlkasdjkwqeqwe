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

Status: deployed and verified. Separate read-only source audit found and verified fixes for list invalidation, discarded-command retention, and activation of the retained parent through its still-mounted Fluent hook. No remaining P1/P2 in the reviewed scope.

Runtime artifacts: `.omo/runtime/returns-reconciliation-20261001/`. Browser spec: `web/e2e/warehouse-workspaces.spec.ts`. Local Firefox configuration is in `web/node_modules/.cache/warehouse-workspaces.config.mjs` (preview port 14190).

Validation:

- Full frontend regression: **775 tests / 140 files passed**.
- TypeScript/Vite production build and lint passed; existing repository lint warnings and large-chunk advisory remain.
- Firefox: **8 desktop/mobile workflows passed**, including retained filters, exact reception and intake payloads, nested case/opening forms, independent discard, and three-layer focus/draft regression.
- Runtime image: every static file matched the tested build manifest; all served successfully over HTTP.
- Guarded release helper: **9 tests passed**.
- Production Firefox: return tabs, intake close/filter retention, reconciliation, and viewport bounds passed at 1910px and 390px, with zero page/API errors and zero business writes. Non-empty workflows were exercised against controlled fixtures locally.

Release:

- Source commit: `4351430b1f0bbbceeb00b4c2867f871244c36d87`.
- Web image: `sha256:43f544782d2dbcc382d1cddae7e5110fce1bbc1951893647f6949449664a60c8`.
- Remote release: `/opt/ftth/setup-private/ui-20261001-returns-4351430b`.
- Exact static manifest/public index and health verified. All **14 non-web containers unchanged**.
- Deployment used the existing guarded SSH frontend-release path. Main's previous Actions deployment failed while uploading release files through Cloudflare Access; this UI correction does not change that deployment configuration.
- Rollback, only when intended: `sudo python3 /opt/ftth/setup-private/ui-20261001-returns-4351430b/deploy-web.py rollback /opt/ftth/setup-private/ui-20261001-returns-4351430b`.

Recovery archive and receipt are retained in the runtime directory and private remote release. They include a verified Git bundle, documentation, screenshots, validation logs, deployment proof and rollback helper; no credentials are included. The exact checkpoint/archive digest is recorded in `recovery-backup.json`.

Keep the full warehouse deploy suite removal from main PR #45 and the map basemap switcher from PR #44.
