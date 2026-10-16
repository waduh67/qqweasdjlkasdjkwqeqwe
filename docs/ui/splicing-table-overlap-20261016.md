# Splicing table column overlap, 2026-10-16

Branch: `fix/splicing-table-overlap-20261016`, based on main `c4846fde` (PR #70).

Fluent Table defaults to fixed column layout. In the splicing lists, single-line endpoint labels could exceed their assigned column width and paint over the adjacent endpoint. Reproduced with a saved joint-box connection: the first endpoint extended roughly 200 px past its column in a narrow panel.

Both Daftar sambungan and Kabel di lokasi now use content-based native table layout. Columns accommodate their full single-line text; the existing keyboard-focusable table wrapper scrolls horizontally when needed. The detail panel retains its own width. The change is scoped to these two lists.

Validation:
- Extended the existing 385/620 px desktop-panel browser cases to save a connection with long cable codes, measure every data cell's text bounds in both tables, verify one-line labels, and exercise horizontal keyboard scrolling without widening the drawer. The added check failed before the fix.
- All 14 Chromium/Firefox splicing scenarios passed after the fix. Inspected the resulting endpoint-column screenshots.
- Production build, lint (existing unrelated warnings), browser-spec typecheck and whitespace checks passed.

Private evidence: `.omo/runtime/splicing-table-overlap/`. Keep changes pushed. Final CI, merge, Actions deployment and live activation evidence will be recorded in the PR. Deploy through GitHub Actions; SSH is only for read-only activation verification.
