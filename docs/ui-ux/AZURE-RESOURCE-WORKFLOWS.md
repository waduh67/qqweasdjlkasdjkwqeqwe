# Azure resource workflows — active UI revision

Branch: `work/azure-resource-workflows`, based on `5beed944`.
This is new work after the previously accepted warehouse delivery; that acceptance does not cover this revision.

Reference: https://gcdnb.pbrd.co/images/pkZMMD5sB9lL.png (1910 × 921).
User requirements: Azure-style warehouse navigation/actions/filters; integrated searchable selectors; full-page creation with details and review; compact aligned fields; concise professional Indonesian copy; plain single-line cells in every application table.

## Coverage and progress

- [ ] Shared searchable warehouse picker: paging, selected off-page value, eligibility, retry and disabled states.
- [ ] Shared resource form: validation before review, retained values, fixed action footer, safe command retries.
- [ ] Warehouse masters and all warehouse document/create flows.
- [ ] Warehouse filters, command bars, advanced options and operational forms.
- [ ] Resource creation outside warehouse: customers, tenant/users/roles/areas, catalog/work orders, inventory/map, BNG/VPN/hotspot, monitoring and provisioning.
- [ ] Remaining contextual create forms: billing, templates, incidents/helpdesk, customer subscriptions, imports and public forms; preserve workflow-specific validation and confirmations.
- [ ] All shared DataTable consumers (72 baseline sites) and direct Fluent tables (14 baseline sites): single-line plain text, preserving values and actions.
- [ ] Focused functional tests, Firefox desktop/mobile and screenshot comparison with reference.
- [ ] Independent review, coherent commits/push, guarded web deployment and production read-only verification.

Current revision is in progress and has not been deployed. The first checkpoint is `57ab2347`.

The shared ResourceForm now keeps Basics mounted during review, preserves captured commands during uncertain retries, and uses a fixed Azure-style footer. Warehouse masters, operational forms, receipt evidence, provenance decisions/openings, and the main nonwarehouse resource editors now use it. Customer, catalog, tenant, IAM, network, hotspot, provisioning, map asset/cable and WhatsApp template editors have explicit review gates before their existing APIs. Searchable picks use one paged combobox; list command bars and table cells are being standardized across the app.

Latest TypeScript + production build passed (`build-provenance.log`). Actual local Firefox SKU/customer create tests passed, including no-write review, retained Back values, native validation and mobile overflow. Regression run 3: 164 passed / 13 failed in the focused 177-test set. Remaining failures mostly reflect changed review/disclosure semantics or labels; finalization and some contextual forms/actions still need coverage. Modal focus lifecycle defects found during QA were fixed, including StrictMode replay and missing/disabled launcher handling. Do not deploy this checkpoint.

Owned Firefox preview remains on ports 14188 / 17880. Continue with the remaining coverage inventory, meaningful regression fixes, final screenshots and independent audit. Private scripts should not be rerun blindly: several are one-time AST/source migrations. Use current files as the source of truth.

## Recovery

Worktree: `/home/fajar/ftth/ftth-ui-ux`.
Private evidence/scripts: `.omo/runtime/ui-ux/azure-warehouse-20260927/` (never commit credentials, build output or raw captures).
Use owned warehouse QA fixtures only; never seed production. Preserve captured warehouse commands and their idempotency keys after uncertain responses. No backend/schema changes are needed for this UI revision.
Production currently serves `0a685bc5`; the old deployment helper is pinned to a different previous image and must be updated from a fresh preflight before any new release.

Commit and push tested coherent chunks with updated recovery notes. Previous warehouse completion evidence and owner-acceptance receipts remain historical and must not be overwritten.
