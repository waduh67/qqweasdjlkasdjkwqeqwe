# Azure resource workflows

Branch: `work/azure-resource-workflows`, based on `5beed944`.
This is new work after the previously accepted warehouse delivery; that acceptance does not cover this revision.

Reference: https://gcdnb.pbrd.co/images/pkZMMD5sB9lL.png (1910 × 921).
Requirements: Azure-style warehouse actions and filters; integrated searchable selectors; full-page creation with Basics/review; compact aligned fields; concise Indonesian copy; plain single-line table cells throughout the app.

## Implemented coverage

- Shared warehouse combobox: server search/paging, selected off-page values, eligibility, retry and disabled states.
- ResourceForm: native and domain validation before review, retained Basics, fixed action footer, draft discard confirmation and locked captured-command retries.
- Warehouse masters, receipts/evidence/inspection, transfers, counts, reservations/material plans, returns/RMA, customer/work-order material actions, provenance/opening/finalization, policies/delegations and replenishment.
- Warehouse list command bars, filters, report periods/reset/export and consistent row action menus. Native disclosure triangles replaced by Fluent accordions.
- Creation outside warehouse: customers, tenants, IAM, areas/plans/work orders, inventory/map/splitters/PON/OTDR, BNG/VPN/routes/forwards, hotspot/vouchers, monitoring/provisioning, templates, tenant billing, customer subscriptions/access/portal credentials and splicing.
- Public signup and portal help requests use inline review while retaining their public-page shell. Existing staged imports/PSB and confirmation-based operational actions retain their domain workflows.
- All inventoried shared DataTable consumers and direct tables use plain single-line values. Inline route/forward editing moved to resource forms. Mobile tables retain headers and horizontal column scrolling.
- Fluent notifications use the body-level AriaLiveAnnouncer so modal focus does not hide feedback. Credential save errors also remain inside the review.

## Verification and checkpoints

Implementation checkpoints: `57ab2347`, `cb81ef65`, `50d6c171`; this document accompanies the final visual verification checkpoint.

TypeScript and Vite production build passed. Firefox comparisons cover nine warehouse pages, seven forms, desktop 1910 × 921 and mobile 390 × 844. Findings fixed: collapsed filter layout, report reset retaining ownership, hidden mobile headers, missing discard protection, modal focus restoration and accessible failed-save feedback. Independent reviewers checked visual and source/workflow behavior.

Final full regression: **126 files / 630 tests passed**. TypeScript and Vite build passed. Exact-dist Firefox core, discard and report checks passed. The final CSS-only correction removed a backdrop tint over the global bar and aligned report action baselines; revised screenshots were compared again. See `design-qa.md`. Release-helper rollback/container-preservation tests: **9 passed**.

Firefox exercises actual SKU/customer writes only against owned local QA fixtures, including no-write review, retained Back values, native validation, page accessibility after save, discard confirmation and mobile overflow. Independent visual audit is read-only; populated operational document coverage is limited by available QA data, supplemented by API-contract regression tests.

## Recovery and release

Worktree: `/home/fajar/ftth/ftth-ui-ux`.
Private evidence/scripts: `.omo/runtime/ui-ux/azure-warehouse-20260927/` (never commit credentials, build output or raw captures).
Owned QA preview uses ports 14188 / 17880 and PID/start-time/marker-guarded cleanup. Do not stop unrelated processes. Never seed production.

Preserve warehouse idempotency keys and captured commands after uncertain responses. No backend/schema changes belong to this revision.

Production now serves `af658039` after guarded activation and authenticated Firefox verification. See `AZURE-RESOURCE-WORKFLOWS-RELEASE.md`. The new private deployment helper checks fresh image/pins, deploys web only, verifies exact served files and public HTTP, preserves every existing unrelated container (including both Drive services), and records guarded rollback state. Do not run the historical helper with stale preflight pins.

Commit/push recovery notes and final release receipt. Previous warehouse owner-acceptance receipts remain historical and must not be overwritten.
