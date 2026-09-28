# Retained resource layers and sidebar preferences

Create/edit panels now retain their original list or detail page. Closing a panel restores its parent without remounting the list or clearing its filters. Resource layers use the shared Fluent dialog focus manager, a breadcrumb trail, guarded back/close controls, and a visible parent edge on desktop. On small screens the active panel fills the content viewport.

Receipt drafts can create and select a supplier, SKU, source location or inspection location in a child panel. Locations can recursively create their parent. Child submission cannot submit the ancestor form; closing a child cannot discard an ancestor. Existing permissions, review steps, ambiguous-command locks, payload decoders and dirty-draft confirmation remain in effect. Nested location choices enforce the same eligible kinds as existing selectors; source creation fixes the required receipt-source code. Metadata loading retains the original focus target.

Sidebar groups expand independently. Navigation opens the active group without closing others. Explicit open/closed choices persist in the existing local browser preference key, including refresh; clearing browser storage resets them. Draft forms are retained during the open workflow, not persisted across a browser reload.

## Validation

Worktree `/home/fajar/ftth/ftth-ui-ux`, branch `work/azure-resource-workflows`. Private evidence: `.omo/runtime/ui-ux/azure-layers-20260928/`.

- Production TypeScript/Vite build passed; existing large-chunk advisory remains.
- Full frontend regression: 643 tests, 129 files passed.
- Local Firefox: retained receipt filter and identical list-read count on close; actual local supplier creation/selection without receipt submission; three independently guarded layers; retained parent drafts; mobile bounds; catalog/transfer/count/return create-close; five sidebar groups survive navigation and refresh, and explicit collapse survives refresh.
- Dedicated tests cover delayed location metadata/focus, eligible nested kinds, nested submit isolation, topmost dismissal, retained receipt list and sidebar storage.
- Independent read-only review closed two P2 findings (nested parent kinds and loading focus), with no remaining actionable P1/P2 in the changed workflow. Root executed runtime QA separately.
- Guarded frontend release helper: nine preservation/rollback tests passed.

Early failed checks were resolved before release: an intermediate JSX delimiter error, stale expectations after preserving background headings, and test interactions that addressed the parent while child metadata was loading. The 100-position count test now uses real select/focus events. Full regression then passed. No production business write is needed for validation.

## Release and recovery

Base checkpoint: `6dd673b348d9833a9057be379a8697efba24792d`. Prior live web image: `sha256:565ac8957e6b07c867feb02b72830a41c6f729d39adcb040dcb676c689d630c4`.

Deployment is frontend only. The helper checks the current image and pins, installs the exact tested static manifest, verifies backend/public health, and compares every non-web container before and after. Activation and source hashes are saved in `release/release.json` and `release/activation.json`; the remote path is saved in `remote-release.txt`.

A recovery Git bundle from the base checkpoint plus docs, screenshots, tests and release receipts is copied to the private remote release directory. The verified archive hash/path is recorded in `recovery-backup.json` and the active run cursor. Preserve the older warehouse task's acceptance; this correction has its own release receipt.

For an intentional rollback only, use `sudo python3 <remote-release>/deploy-web.py rollback <remote-release>` on the production host. The helper checks for foreign image/pin changes before reverting.
