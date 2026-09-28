# Compact Azure warehouse filters

The latest user screenshots require actions above search/filter chips, a compact gap, and no global Apply/Reset buttons. This correction replaces warehouse filter forms with one shared chip bar and keeps the existing backend filter contracts.

## Scope

Catalog and access lists, stock, receipts, material requests, replenishment, transfers, returns, counts, approvals, reports and delegation filters. Actions sit above a 24px search/chip row with a 10px gap. Extra criteria are added through Add filter. Single criteria apply immediately; removing one retains the others. Searches debounce 250ms; valid complete date pairs apply atomically. CSV export remains tied to current report filters.

Reference selectors use the existing paged searchable pickers. Applied URL references stay visible and removable when directory access is absent or fails. Browser Back cancels pending searches and restores open date editors. Closing a serial popup retains the typed filter; removing the filter cancels it.

## Validation and recovery

Worktree `/home/fajar/ftth/ftth-ui-ux`, branch `work/azure-resource-workflows`.
Private runtime `.omo/runtime/ui-ux/azure-filterbar-20260928/` contains references, screenshots, test/build logs, reviewer note, release helpers, source/artifact hashes and deployment receipts. Never commit credentials or the runtime directory.

Production base before this correction: source `af6580398d4c77aff4f928082c5a88a17b9711f6`, web image `sha256:16f050ef809af962b60fc5947da58695109c87f079ca6245b17a399da7121f6c`, release `/opt/ftth/setup-private/ui-20260927-resources-af658039`.

At this source checkpoint, frontend build and local Firefox checks pass. Full regression rerun is in progress after adapting the remaining legacy delegation selector. Independent reviewer closed the three navigation/reference findings with no remaining P1/P2. The final deployment receipt and test totals will be appended below after completion.

Deployment uses the existing guarded frontend-only release helper. It checks the current image/pin hash, installs the verified static manifest, compares every non-web container, and verifies backend health plus public FTTH/Drive endpoints. If activation fails it restores the saved web pins. No backend, database or schema change is required.

Do not rerun the old warehouse task or invalidate its historical acceptance. Resume this correction from its own runtime/release receipt. Use `release/deploy-web.py rollback <remote-release>` on the remote host only when intentionally reverting this release; the helper rejects foreign pin/image changes.
