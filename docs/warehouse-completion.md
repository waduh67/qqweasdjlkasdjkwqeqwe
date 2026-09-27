# Warehouse completion checkpoint — 27 September 2026

The original 48 implementation tasks and independent F1–F4 audits reached technical
closure at `b38d8eb963b7d1e0e1365e37237be1b765ee0697`. The owner subsequently rejected
the old UI and requested the Azure-style revision. Final owner acceptance remains
pending; historical technical approval is not owner acceptance of the revised UI.

The current web product is deployed from
`329039f21d77839e8421c44b7465fe492b61ab60`; see the
[controls release](ui-ux/CONTROLS.md) and [operations guide](../deploy/AZURE-FTTH-OPERATIONS.md).
Backend, migrations, shared mobile code, protocol collectors, Gradle and CI inputs
remain byte-identical to the audited warehouse baseline. Their retained evidence
remains applicable. Changes to shared frontend components require renewed browser
and frontend checks.

## Current work

Worktree: `/home/fajar/ftth/ftth-ui-ux`, branch `work/ui-ux-revision`.
Private evidence: `.omo/runtime/ui-ux/warehouse-completion-20260927/` in that worktree.
The first full frontend run passed 620/621 cases; the RMA dialog case passed alone.
Its click sequence now uses real user-event focus transitions. Two setup-browser
assertions were also updated to the current empty approval message, preserving the
real backend count and permission assertions. Failed attempts are retained privately.

The previously independently executed partial-return/reassignment/count scenario is
now a portable regression in `web/e2e/warehouse/f3-extra-edge.spec.ts`. It forwards a
real return command before dropping only the response, checks an identical retry,
and proves that 60m remains with the original technician while 40m is returned and
counted independently. Saved evidence omits fixture credentials.

Next: run the full frontend suite, all nine main warehouse browser specs, this extra
edge spec, and the legacy browser cutover with Firefox under the existing serial
QA lease. Bind reports to the clean source commit and exact deployed static build;
preserve failed attempts and successful artifacts separately. Obtain independent
review of renewed evidence, then present the review guide for final owner acceptance.
No product deployment is required for these test and documentation changes.

QA uses the isolated environment managed by `scripts/warehouse/test-environment.sh`
and `scripts/warehouse/qa.sh`; never run transaction fixtures on production. Keep
credentials, raw reports, database snapshots and `.omo` files out of Git. The outer
host lease is
`/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock`.
The original 48-row source/evidence mapping and all eleven canonical proof hashes
were checked again on 27 September; no backend or contract gap was found.

The next checkpoint passed all 621 frontend cases across 123 files, four setup
cases and two receiving cases. Both issue cases reached successful dispatch and
slip retrieval, then headless Firefox rejected navigation while its native print
dialog was active. The issue test now captures the actual printable DOM at the
OS print boundary and verifies slip code, recipient, serial, quantity and state;
the real server slip request remains required. This validates printable content,
not a physical printer. Resume issue and remaining browser cases; unchanged
frontend/setup/receiving evidence remains bound to `79d5ccae`.

Issue now passes in both Firefox viewports at `885a1f34`. Returns passed three
cases, but its mobile reassignment helper tried to dismiss an open technician
picker by clicking a heading covered by that picker. The helper now presses Escape
and requires `aria-expanded=false` before the unchanged real assignment command.
Only returns and the extra edge scenario import this helper. Resume returns onward;
preserve the completed issue result and earlier unaffected evidence with their
actual executed commits. No product code changed.

The follow-up exposed an actual narrow-screen layout defect: Fluent Combobox's
default 250px minimum width extends beyond its shrinking form column and covers
the reassignment button. Searchable single/multiple pickers now use `min-width: 0`
within their existing 100% width. The real reassignment regression also checks
that picker and submit rectangles do not overlap and captures that section before
clicking. This CSS fix requires a new tested web build and deployment; prior
production remains the controls release until the fix is verified and activated.

At `0a685bc5`, all 621 frontend cases and the nine main warehouse specs (24 browser
cases) pass on the corrected product. The extra offline-return test found its old
background-field locator incompatible with Fluent's accessible modal isolation.
The test now waits for completed preflight and visible confirmation, checks the
retained disabled field with hidden elements included, and separately verifies the
visible 40 m destination summary. All original transaction/replay/stock assertions
remain. Preserve this failed attempt; rerun only the extra scenario and remaining
visual/legacy gates. This is a test-only delta; the frozen product remains `0a685bc5`.
