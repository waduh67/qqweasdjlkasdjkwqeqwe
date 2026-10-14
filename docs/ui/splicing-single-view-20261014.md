# One-page splicing workspace, 2026-10-14

Branch: `fix/splicing-single-view-20261014`, based on main `e658408b` (PR #68).

The user requested removing the Penyambungan / Daftar sambungan / Kabel di lokasi tabs and reported clipped content in the joint-box detail panel. The interactive workbench, connection table and cable inventory now appear together, in that order. Each table has its own search and selected row; commands explicitly target either a connection or a cable. Filtering one table does not change the other table or the pending interactive pair. Existing read/manage permissions, endpoint protection and save/disconnect behavior are preserved.

Splicing layout now responds to its container width instead of the browser viewport. A narrow panel on a desktop screen stacks source, destination and the connection preview. Narrow command bars use their overflow menu, and each table scrolls within its own keyboard-focusable region. The shared network detail wrapper and its direct children are constrained to the available width, preventing a child from stretching the detail panel. No overflow-hiding workaround, API change or production topology edit.

Validation:
- 18 targeted tests passed, including direct splicing, local availability, stale selections, independent cable filtering, permissions and capacity.
- 14 Firefox/Chromium scenarios passed: 375/1440 px viewports, simultaneous lists, keyboard use, saving/retry, independent table selection, edit/disconnect and read-only access. Dedicated 385/620 px desktop-panel cases use long joint-box cable codes and occupied cores; they verify stacked layout, content bounds and keyboard table scrolling.
- Production build, lint (pre-existing unrelated warnings only), browser-spec typecheck and whitespace checks passed. Desktop and narrow-panel screenshots inspected.

Private evidence: `.omo/runtime/splicing-single-view/`. Keep commits pushed. Final CI, merge, Actions deployment and live activation evidence will be recorded in the PR. Deploy through GitHub Actions; SSH is only for read-only activation verification.
