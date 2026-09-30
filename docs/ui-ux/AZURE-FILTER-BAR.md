# Compact Azure warehouse filters

The latest user screenshots require actions above search/filter chips, a compact gap, and no global Apply/Reset buttons. This correction replaces warehouse filter forms with one shared chip bar and keeps the existing backend filter contracts.

## Scope

Catalog and access lists, stock, receipts, material requests, replenishment, transfers, returns, counts, approvals, reports and delegation filters. Actions sit above a 24px search/chip row with a 10px gap. Extra criteria are added through Add filter. Single criteria apply immediately; removing one retains the others. Searches debounce 250ms; valid complete date pairs apply atomically. CSV export remains tied to current report filters.

Reference selectors use the existing paged searchable pickers. Applied URL references stay visible and removable when directory access is absent or fails. Browser Back cancels pending searches and restores open date editors. Closing a serial popup retains the typed filter; removing the filter cancels it.

## Validation and recovery

Worktree `/home/fajar/ftth/ftth-ui-ux`, branch `work/azure-resource-workflows`.
Private runtime `.omo/runtime/ui-ux/azure-filterbar-20260928/` contains references, screenshots, test/build logs, reviewer note, release helpers, source/artifact hashes and deployment receipts. Never commit credentials or the runtime directory.

Production base before this correction: source `af6580398d4c77aff4f928082c5a88a17b9711f6`, web image `sha256:16f050ef809af962b60fc5947da58695109c87f079ca6245b17a399da7121f6c`, release `/opt/ftth/setup-private/ui-20260927-resources-af658039`.

TypeScript/Vite production build passed. Full Vitest regression passed: 636 tests in 127 files. After the final export action styling, all six report tests passed again. Local Firefox passed 14 captures across ten warehouse lists and popup/mobile states. Independent reviewer closed the navigation/reference findings with no remaining P1/P2. The guarded release helper passed nine rollback/preservation tests.

Deployment uses the existing guarded frontend-only release helper. It checks the current image/pin hash, installs the verified static manifest, compares every non-web container, and verifies backend health plus public FTTH/Drive endpoints. If activation fails it restores the saved web pins. No backend, database or schema change is required.

Do not rerun the old warehouse task or invalidate its historical acceptance. Resume this correction from its own runtime/release receipt. Use `release/deploy-web.py rollback <remote-release>` on the remote host only when intentionally reverting this release; the helper rejects foreign pin/image changes.

## Deployed result

- Source: `15aa445db59c4cf812613d3ec71f7d529f9c63d5` (pushed).
- Live image: `sha256:565ac8957e6b07c867feb02b72830a41c6f729d39adcb040dcb676c689d630c4`.
- Remote release: `/opt/ftth/setup-private/ui-20260928-filterbar-15aa445d`.
- Exact tested static manifest and public index verified. All 14 non-web containers retained their IDs, images, start times and restart counts. Backend health and public FTTH/Drive checks passed.
- Production Firefox: 15 captures; automatic status selection, independent removal, pending search/Back, retained focus, quick serial/Escape, date completion, open date editor/Back and mobile bounds passed. Zero page errors or business-write attempts.
- The first production date assertion assumed UTC for a local-time input. The UI correctly used Asia/Jakarta; the corrected browser-timezone assertion passed in the full rerun. No product correction was required.
- Owned local preview/backend processes stopped through their validated wrapper; process records removed, QA volumes retained. No unrelated process was paused.

Recovery bundle base is `90b7b41ff5f9898f694238c06f59c9c044cceb4d`. The final documentation checkpoint is bundled with release/test/browser evidence and copied under `recovery-<checkpoint8>/recovery.tar.gz` in the remote release directory. Its SHA and exact location are recorded privately in `recovery-backup.json` and the active run cursor. The deployment tar and guarded rollback helper remain under the remote release. Nothing remains to deploy for this correction.
