# Interactive fiber splicing, 2026-10-14

Branch: `fix/interactive-splicing-20261014`, based on main `eb8d7dfc` (PR #67).

The user found the new connection wizard harder to understand than selecting physical fibers. Single connections now start on an inline interactive workbench: choose a colored core/port on the source side, choose a compatible destination, inspect the pair in the center and press **Sambungkan**. There is no creation modal, step navigation or separate review page. Clicking a point does not write to the server.

Purpose buttons keep splitter input, output, cable continuation, cascading and ODF work distinct. Invalid destinations are omitted; the source cable cannot appear on the opposite side. Availability remains local to the closure, so a remote-end connection does not block a usable core. Occupied/damaged points and unopened cables remain excluded. Number labels, selected checkmarks and keyboard interaction supplement color.

Optional method, loss, notes and work-order fields expand inline. Method/work order persist for repeated work; point selection, per-connection loss and notes reset after success. A failed save retains the pair and details. Selected points are resolved against the current workbench on every render; refresh cannot submit a now-occupied point. Existing capacity, permissions, bulk numbering, metadata editing and cable attachment protections remain unchanged.

The default **Penyambungan** tab contains the workbench. **Daftar sambungan** retains the one-line table and edit/disconnect command bar. **Kabel di lokasi** retains cable attachment management. The workbench stays mounted while visiting another tab, preserving the pending selection. Read-only users see the lists without creation controls.

The old single-connection form was removed; shared metadata and searchable controls moved to `SpliceDetailFields.tsx`. Bulk splicing and maintenance still use their existing dedicated blades. No API, backend or schema change.

## Verification and recovery

- 18 targeted tests passed, covering direct selection/save, duplicate submission, purpose resets, same-cable exclusion, local availability, stale selections, capacity, optional loss, failures and permissions.
- 10 Firefox/Chromium scenarios passed, including 375/1440 px layouts, selection retention, keyboard use, optional details, save retry, edit/disconnect and read-only access.
- Build, lint (pre-existing unrelated warnings only) and browser-spec typecheck passed. Desktop/mobile screenshots inspected; fixed core-button widths keep one- and two-digit core numbers aligned. Recheck browser output after this final CSS adjustment.

Private evidence: `.omo/runtime/interactive-splicing/`. Final CI, merge, Actions deployment and live activation evidence will be recorded in the PR. Release through GitHub Actions only; use SSH only for read-only activation verification. Keep commits pushed so another agent can resume if the session ends.
