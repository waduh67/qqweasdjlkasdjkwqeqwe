# Azure warehouse filter bar QA

Final visual result: passed for the compact command/search/filter correction. Release evidence is recorded in `docs/ui-ux/AZURE-FILTER-BAR.md`.

## Reference and comparison

References supplied by the user:
- https://gcdnb.pbrd.co/images/leE18BR4Xko4.png
- https://gcdnb.pbrd.co/images/KPaof3VfAKNP.png

Private evidence: `.omo/runtime/ui-ux/azure-filterbar-20260928/`. Source files `reference-1.png` and `reference-2.png` are 1910 × 921. Firefox implementation captures use the same desktop viewport, scale 1, light theme; mobile uses 390 × 844. Reference and implementation images were opened together in the same comparison inputs, including the final report actions and open catalog filter. This matches the command/filter region; the existing product navigation and warehouse-specific content remain different from Azure's VM page.

## Findings and fixes

1. Commands and filters previously occupied separate large stack gaps, with global Apply/Reset controls. They now share a zero-gap wrapper: command separator, 10px padding, then 24px search and rounded blue filter chips. Secondary criteria live under Add filter. No global Apply/Reset controls remain in warehouse lists.
2. Filters no longer require form submission. Single selections update server queries immediately, search is debounced 250ms, and dates publish only complete valid intervals. A chip's remove control clears its own criterion. Date-only end dates remain inclusive in the UI and exclusive in requests.
3. Pending search could overwrite browser Back, open date drafts could retain a previous URL interval, and selected URL references could be invisible without directory permission. POP navigation resets the draft/timer; date endpoints follow URL state; reference chips retain removable IDs without unauthorized lookups. Independent read-only source review closed all three P2 findings.
4. A serial entered just before closing its popup could be discarded. The debounce now belongs to the filter, survives popup dismissal, and cancels when the filter is removed. Dedicated regression and Firefox quick-Escape checks pass.
5. Final visual comparison found report Export CSV still styled as a bordered form button. It now uses a flat icon/text action beside Refresh, and the extra top separator is removed. Final report capture was regenerated after this correction.

## Fidelity and behavior

- 180px search, 24px controls/chips, 8px horizontal spacing, 10px command-to-filter gap, blue action icons and pale-blue chips.
- Existing Segoe-based Fluent typography; 13px control text, regular labels, semibold selected values. No claim of identical raster rendering across OS/browser fonts.
- Popovers use a 520px desktop surface constrained to viewport minus 32px; mobile wraps chips and stacks date controls with no page overflow.
- Azure's reference supports multi-selection and operators. Current APIs support single criteria; popovers expose those real choices and apply immediately as requested, without decorative unsupported operators.
- Existing paged searchable master pickers remain one combined search/select field. Historical directory queries retain archived masters where supported.
- No actionable P0/P1/P2 remains in the reviewed command/filter scope. This is not a full accessibility-conformance audit. Current operational rows are sparse in the local fixture; filter payloads, permissions, paging and dates are covered by regression tests.

## Evidence

`browser-report.json` and `browser-final.log`: ten warehouse lists, catalog selection, individual removal, pending search/Back, retained focus, quick serial/Escape, date completion and mobile. Fourteen captures, zero page errors and zero business writes. `ResourceFilters.test.tsx` covers automatic selection, independent removal/focus, quick dismissal, browser reset, valid dates and restored intervals. Full regression, build and guarded release results are in the release handoff.

Previous create-form/table audit remains in Git at `90b7b41f:design-qa.md` and `docs/ui-ux/AZURE-RESOURCE-WORKFLOWS-RELEASE.md`.

Production verification: source `15aa445db59c4cf812613d3ec71f7d529f9c63d5` is live. The exact static manifest was verified, then 15 authenticated Firefox captures and filter interactions passed with no page errors, business writes or page overflow. Full regression: 636/636 tests across 127 files; final report follow-up: 6/6; guarded deployment helper: 9/9. Production screenshots share the private runtime with the source references.
