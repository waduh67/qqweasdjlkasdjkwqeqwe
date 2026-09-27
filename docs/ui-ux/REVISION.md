# UI/UX correction — September 2026

User rejected the complete interface after the warehouse release. Passing business regressions does not establish usability or visual acceptance. This revision covers the existing platform, tenant and customer portal interfaces.

Branch: `work/ui-ux-revision`, based on `b38d8eb9`. Edit worktree: `/home/fajar/ftth/ftth-ui-ux`.

## Evidence and direction

Fresh production desktop (1440 px) and mobile (390 px) captures are private in `.omo/runtime/ui-ux/`. Baseline tenant onboarding and VPN forms visibly clip fields. Dashboard presents repeated zeros and configuration as a metric; sidebar hides destinations by default. Email settings combine four distinct tasks into a long page. Independent source audit also identified dense technician tables, ambiguous network menu names, incomplete lists and completion controls preceding work evidence.

Keep Fluent UI, Azure blue branding, actual routes, permissions and business safeguards. Use readable navigation groups with distinct icons; expose the active destination. Use page headers with a clear action, forms grouped by purpose and panel-width responsive columns, tables with aligned headers and row actions at the end. Separate loading, failure and empty data. Progressive disclosure must retain unsaved inputs and all existing actions.

## Checkpoint 1 — saved for continuation

- Shared field containment, semantic page headings, form spacing and navigation discovery.
- Platform dashboard empty state and honest page-scoped metrics.
- Tenant onboarding grouped into identity, admin and subscription; native submission, busy state and inline error.
- VPN fields laid out responsively. Email sections separated into tabs without unmounting drafts.
- Shared table alignment, sorting indicators and constrained action column.

Not yet accepted or deployed. Remaining: tenant page families, warehouse queues/forms, technician workflow, portal, real desktop/mobile visual and keyboard checks, full frontend checks, independent review, deployment with rollback checkpoint. Existing warehouse/backend technical acceptance remains historical; it is not UI acceptance.

## Verification so far

First shared/navigation iteration: 608 tests in 117 frontend suites passed; 9 focused navigation, shell and tenant checks passed. A fresh build after the technician layout changes passed. The full-suite run overlapped those last layout edits, so repeat affected checks before release. Current screenshot review caught and corrected mobile header flex height, drawer footer width and table alignment; recapture is required. Private preview on 127.0.0.1:5174 proxies authenticated production reads and rejects non-auth writes; no business data is submitted during visual capture. Use isolated local backend for transaction regression.
