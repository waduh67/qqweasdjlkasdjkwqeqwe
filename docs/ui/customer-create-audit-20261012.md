# Customer detail, creation forms, and account passwords — 12 October 2026

Worktree: `/home/fajar/ftth/ftth-azure-forms-20261012`.
Branch: `fix/azure-customer-forms-20261012`, based on main `f3672a72`.

## Requested outcome

- Rebuild customer detail using Azure resource navigation, grouped content, plain tables, compact controls, and concise Indonesian copy.
- Audit every creation entry point across tenant, platform, network, warehouse, map, and public/portal flows. Long forms need meaningful steps, retained drafts, validation before review, and consistent footers. Short forms remain short.
- Add self-service password change and tenant admin password reset; make the existing platform owner reset discoverable. Preserve tenant boundaries, owner protection, second factors, audit records, and immediate session invalidation.
- Commit/push checkpoints, run relevant regression/browser checks, merge to main and deploy through GitHub Actions. Do not seed production business data or reset real accounts during testing.

## Checkpoints

- [x] Isolated worktree and initial source inventory.
- [x] Password backend, permission and regression coverage: 8 integration tests passed.
- [x] Account/user/tenant password interfaces: 6 focused UI tests passed; own, tenant-staff and platform-owner browser flows verified.
- [x] Customer detail layout and retained section workflows.
- [x] Shared form spacing and grouped-step support; native fields and required selections validate before advancing.
- [x] All creation entry points reviewed; complex forms grouped explicitly.
- [x] Desktop/mobile Firefox and Chromium verification: 30 scenarios passed (15 per browser), no skipped or flaky cases.
- [x] Full frontend checks and relevant backend checks: 944 frontend tests in CI, 8 backend password/owner integration tests, lint and production build passed.
- Release tracking: [PR #61](https://github.com/waduh67/qqweasdjlkasdjkwqeqwe/pull/61). This document is committed before deployment; the merged commit and production `deploy` workflow are the release authority.

## Source inventory

Panel counts include detail/edit panels; each file must be inspected to identify its creation flows. Field counts are an initial heuristic and exclude fields in child components. Login is outside the create-resource audit; signup and portal ticket creation are included. Existing safe flows should be preserved, including warehouse command identity/replay locks.

| Source | Panels | Direct fields | Review |
|---|---:|---:|---|
| `web/src/pages/AccountSecurityPage.tsx` | 1 | 2 | Source reviewed; short retained form |
| `web/src/pages/AreasPage.tsx` | 1 | 2 | Source reviewed; short retained form |
| `web/src/pages/BngConfiguration.tsx` | 1 | 1 | Source reviewed; detail/edit or short action |
| `web/src/pages/CatalogPage.tsx` | 1 | 26 | Source reviewed; named steps and regression verified |
| `web/src/pages/CustomerDetailPage.tsx` | 3 | 13 | Source reviewed; short retained form |
| `web/src/pages/CustomersPage.tsx` | 1 | 8 | Source reviewed; named steps and regression verified |
| `web/src/pages/HotspotPage.tsx` | 3 | 10 | Source reviewed; named steps and regression verified |
| `web/src/pages/InventoryPage.tsx` | 8 | 25 | Source reviewed; named steps and regression verified |
| `web/src/pages/InvoicesPage.tsx` | 6 | 6 | Source reviewed; short retained form |
| `web/src/pages/MapPage.tsx` | 3 | 0 | Source reviewed; detail/edit or short action |
| `web/src/pages/MonitoringPage.tsx` | 1 | 1 | Source reviewed; short retained form |
| `web/src/pages/MyMaterialReceipt.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/MyMaterialResidualAcknowledgement.tsx` | 1 | 1 | Source reviewed; short retained form |
| `web/src/pages/MyMaterialReturn.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/MyMaterialRma.tsx` | 1 | 1 | Source reviewed; short retained form |
| `web/src/pages/OltDetailPage.tsx` | 2 | 17 | Source reviewed; short retained form |
| `web/src/pages/PaymentGatewaySettingsPage.tsx` | 2 | 21 | Source reviewed; named steps and regression verified |
| `web/src/pages/PlatformAcsSettingsPage.tsx` | 0 | 4 | Source reviewed; detail/edit or short action |
| `web/src/pages/PlatformEmailSettingsPage.tsx` | 1 | 7 | Source reviewed; detail/edit or short action |
| `web/src/pages/RadiusServersPage.tsx` | 1 | 12 | Source reviewed; named steps and regression verified |
| `web/src/pages/RolesPage.tsx` | 1 | 2 | Source reviewed; named steps and regression verified |
| `web/src/pages/SignupPage.tsx` | 0 | 4 | Source reviewed; short retained form |
| `web/src/pages/TaxSettingsPage.tsx` | 1 | 1 | Source reviewed; detail/edit or short action |
| `web/src/pages/TenantsPage.tsx` | 1 | 1 | Source reviewed; named steps and regression verified |
| `web/src/pages/UsersPage.tsx` | 2 | 5 | Source reviewed; named steps and regression verified |
| `web/src/pages/VpnCredentialsBlade.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/VpnPage.tsx` | 5 | 9 | Source reviewed; short retained form |
| `web/src/pages/VpnServersPage.tsx` | 1 | 6 | Source reviewed; named steps and regression verified |
| `web/src/pages/WorkOrdersPage.tsx` | 1 | 10 | Source reviewed; named steps and regression verified |
| `web/src/pages/b2b/B2BClientsPage.tsx` | 1 | 7 | Source reviewed; named steps and regression verified |
| `web/src/pages/b2b/B2BVisitsPage.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/b2b/shared.tsx` | 2 | 0 | Source reviewed; short retained form |
| `web/src/pages/helpdesk/TicketBlade.tsx` | 2 | 5 | Source reviewed; short retained form |
| `web/src/pages/warehouse/MaterialPlanEditor.tsx` | 1 | 5 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/ReferenceCountEditor.tsx` | 2 | 4 | Source reviewed; short retained form |
| `web/src/pages/warehouse/ReferenceReceiptEditor.tsx` | 1 | 4 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/ReferenceRequestEditor.tsx` | 1 | 9 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/ReferenceRequestMovements.tsx` | 3 | 7 | Source reviewed; short retained form |
| `web/src/pages/warehouse/ReferenceRequestReview.tsx` | 2 | 3 | Source reviewed; short retained form |
| `web/src/pages/warehouse/ReferenceReturnEditor.tsx` | 2 | 4 | Source reviewed; short retained form |
| `web/src/pages/warehouse/ReferenceSettingsPage.tsx` | 1 | 2 | Source reviewed; short retained form |
| `web/src/pages/warehouse/ReferenceTransferEditor.tsx` | 1 | 5 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/WarehouseActivationEditor.tsx` | 1 | 1 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseAllocationEditor.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseApprovalsPage.tsx` | 1 | 1 | Source reviewed; detail/edit or short action |
| `web/src/pages/warehouse/WarehouseCountEditor.tsx` | 1 | 5 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/WarehouseCountsPage.tsx` | 1 | 2 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseCustomerRma.tsx` | 1 | 6 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseDelegationForm.tsx` | 1 | 6 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/WarehouseDispositionForm.tsx` | 1 | 4 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseDrainDialog.tsx` | 1 | 0 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseLocationEditor.tsx` | 1 | 8 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/WarehouseSource reviewed; detail/edit or short actionMaterialReturns.tsx` | 1 | 0 | Source reviewed; detail/edit or short action |
| `web/src/pages/warehouse/WarehouseProvenanceCase.tsx` | 3 | 8 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseProvenanceOpening.tsx` | 4 | 5 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseProvenancePage.tsx` | 1 | 0 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseReceiptActions.tsx` | 1 | 7 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseReceiptEditor.tsx` | 1 | 15 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/WarehouseReceiptEvidence.tsx` | 1 | 0 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseReplenishmentRuleForm.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseReservationEditor.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseReturnActions.tsx` | 1 | 9 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseReturnEditor.tsx` | 4 | 3 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseReturnReacquisition.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseReturnsPage.tsx` | 1 | 0 | Source reviewed; detail/edit or short action |
| `web/src/pages/warehouse/WarehouseSettingsPage.tsx` | 1 | 3 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/WarehouseSkuEditor.tsx` | 1 | 8 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/WarehouseSupplierEditor.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseSupplierReplacement.tsx` | 1 | 8 | Source reviewed; named steps and regression verified |
| `web/src/pages/warehouse/WarehouseTransferActions.tsx` | 1 | 5 | Source reviewed; short retained form |
| `web/src/pages/warehouse/WarehouseTransferEditor.tsx` | 1 | 7 | Source reviewed; named steps and regression verified |
| `web/src/pages/workorder/ReferenceWorkAssignment.tsx` | 1 | 1 | Source reviewed; short retained form |
| `web/src/pages/workorder/ReferenceWorkDispatch.tsx` | 2 | 4 | Source reviewed; short retained form |
| `web/src/pages/workorder/ReferenceWorkEditor.tsx` | 2 | 8 | Source reviewed; named steps and regression verified |
| `web/src/pages/workorder/ReferenceWorkTypesPage.tsx` | 1 | 6 | Source reviewed; short retained form |
| `web/src/components/organisms/AccessNodeDetail.tsx` | 1 | 12 | Source reviewed; detail/edit or short action |
| `web/src/components/organisms/OdfDetail.tsx` | 1 | 5 | Source reviewed; detail/edit or short action |
| `web/src/components/organisms/PortalCredentialCard.tsx` | 1 | 2 | Source reviewed; short retained form |
| `web/src/components/organisms/ReleaseDropDialog.tsx` | 1 | 2 | Source reviewed; short retained form |
| `web/src/components/organisms/SplicingManager.tsx` | 1 | 8 | Source reviewed; short retained form |
| `web/src/components/organisms/SplitterPanel.tsx` | 1 | 2 | Source reviewed; short retained form |
| `web/src/components/organisms/TenantOwnerPanel.tsx` | 1 | 0 | Source reviewed; detail/edit or short action |
| `web/src/components/organisms/TenantOwnerPasswordForm.tsx` | 0 | 2 | Source reviewed; detail/edit or short action |
| `web/src/components/organisms/TenantSubscriptionModal.tsx` | 1 | 5 | Source reviewed; short retained form |
| `web/src/components/organisms/WhatsAppTemplateCard.tsx` | 1 | 5 | Source reviewed; short retained form |
| `web/src/components/organisms/customer/CustomerAssetAction.tsx` | 1 | 1 | Source reviewed; short retained form |
| `web/src/components/organisms/customer/CustomerAssetException.tsx` | 1 | 2 | Source reviewed; short retained form |
| `web/src/components/organisms/customer/CustomerAssetInstallation.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/components/organisms/map/CablePanel.tsx` | 1 | 1 | Source reviewed; detail/edit or short action |
| `web/src/components/organisms/map/OtdrSection.tsx` | 1 | 5 | Source reviewed; short retained form |
| `web/src/components/organisms/map/PlaceAssetForm.tsx` | 1 | 21 | Source reviewed; named steps and regression verified |
| `web/src/components/organisms/map/SaveCablePanel.tsx` | 1 | 5 | Source reviewed; named steps and regression verified |
| `web/src/components/organisms/provisioning/ProvisioningEditors.tsx` | 1 | 10 | Source reviewed; named steps and regression verified |
| `web/src/components/organisms/provisioning/ProvisioningEvidencePanels.tsx` | 1 | 1 | Source reviewed; short retained form |
| `web/src/components/organisms/warehouse/WarehouseCommandDialog.tsx` | 1 | 0 | Source reviewed; short retained form |
| `web/src/components/organisms/workorder/WorkOrderMaterialHandover.tsx` | 1 | 4 | Source reviewed; short retained form |
| `web/src/components/organisms/workorder/WorkOrderMaterialRework.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/components/organisms/workorder/WorkOrderMaterialUse.tsx` | 1 | 3 | Source reviewed; short retained form |
| `web/src/components/organisms/workorder/WorkOrderMaterials.tsx` | 0 | 1 | Source reviewed; detail/edit or short action |
| `web/src/components/organisms/workorder/WorkOrderSignatureUpload.tsx` | 0 | 2 | Source reviewed; short retained form |
| `web/src/portal/PortalForgotPasswordPage.tsx` | 0 | 4 | Source reviewed; detail/edit or short action |
| `web/src/portal/PortalHelpTab.tsx` | 0 | 4 | Source reviewed; short retained form |
| `web/src/portal/PortalProfilPage.tsx` | 0 | 4 | Source reviewed; detail/edit or short action |

## Password implementation checkpoint

- Self service: `POST /api/me/password` verifies the current password. Password change revokes all access/refresh sessions and leaves 2FA/status intact.
- Tenant reset: `POST /api/users/{id}/reset-password` requires `iam.user.resetpassword`; other tenants, tenant owners, platform admins and self-reset are rejected. Existing technician-only endpoint remains unchanged.
- The new permission is seeded through PermissionCatalog; default Admin receives it once through the default-role feature marker. No existing custom role permissions are replaced.
- Platform reset retains `expectedOwnerUserId` on the existing owner endpoint. It is now exposed from tenant row actions.
- Security pages are available in both tenant and platform navigation. Passwords remain masked and are never placed in review summaries, logs, or browser storage.
- Evidence: `.omo/runtime/azure-forms/password-server-tests.log`, `password-ui-tests.log`, and `password-build.log`.

## Customer layout checkpoint

- Customer detail now uses a retained resource panel and vertical section navigation (compact picker on mobile). Overview, subscriptions, devices, portal, network, billing and activity no longer share one overloaded summary.
- Asset history is a plain resource table with row actions and nested details; warehouse action endpoints, command signatures and permissions are preserved. Connected hosts and ACS action logs also use tables.
- WiFi editing is a nested panel; failures retain edits. Portal login reset is a single reviewed action and generated credentials use a resource panel.
- Customer asset regression: 7 tests passed; build passed. Rich browser fixtures and remaining customer sections still need verification.

## Shared creation-flow checkpoint (work remains)

- Added named, retained steps to ResourceForm/Blade. ResourceStep and FormSection can group domain fields without replacing existing API/warehouse command handlers. Native validation, required lookup choices, dirty-close guards, nested submit isolation and uncertain-command review locks are preserved.
- Applied named steps to customer/user/tenant/role/catalog, RADIUS/VPN/hotspot/OLT, map asset/cable, provisioning intent, legacy/reference work orders, B2B clients, payment onboarding, warehouse catalog/receipts/requests/transfers/replacement/delegation/policy/count/material-plan forms.
- Payment profile registration now has Business/Contact/Bank steps. Draft profile saving remains available separately from registration, and failed requests retain the form.
- Initial source inventory inspected (103 files). Rows above record source review only; they do not imply final browser/regression acceptance. Additional entry points: BngEditor already uses ResourceWizard; PlaceCustomerForm selects an existing customer; remaining map panels are detail/action wrappers. ExpressPsbPage/onboarding child fields were additionally audited: the 17-field inline form was migrated to a retained five-step resource flow, with coordinate validation, masked credential review and explicit result-based closure.
- Evidence: 943-test baseline run had 920 passes and 23 failures caused by changed navigation/control locations. Updated affected fixtures passed 56/57; the last catalog access fixture was corrected and its 11-test core run passed. More forms were grouped after that run, so full regression must be repeated and repaired before merge.
- Browser: own password failure/masking and tenant admin reset passed at 375/1440 in Firefox and Chromium (4 scenarios). Customer resource navigation, nested subscription/asset panels and plain asset table passed the same matrix (4 scenarios). Screenshots under web/test-results; captures should disable animations to avoid translucent entrance frames.
- Still required: final copy cleanup, remaining long-form browser coverage including payment/OLT/warehouse, platform owner reset browser scenario, all relevant unit/DOM tests and lint/build, production merge/deploy/verification. No production mutation or deployment has been performed for this branch yet.

- Additional checkpoint: PSB Ekspres now opens from its command bar, uses Customer/Location/Plan/Network/Installation steps and clears drafts only on confirmed discard or successful creation. The shared step definition now supports domain validation such as required map coordinates.
- Latest full regression is in `.omo/runtime/azure-forms/frontend-regression-2.log` (in progress when checkpoint written). Newly grouped warehouse settings/count/transfer/material forms require test navigation updates. Do not merge until the whole suite is green.

## Regression checkpoint, 2026-10-12

- Updated tests follow the new named steps while retaining payload, permission, conflict, revision and uncertain-retry assertions. All six affected warehouse suites passed (47 tests across the focused runs).
- Required warehouse lookup choices now have a regression proving that typed search text is not a selected location, followed by domain coordinate validation. ResourceSteps: 3 passed.
- Stable Firefox/Chromium screenshots at 375/1440 verified customer navigation, retained child panels, password change/reset, five-step PSB failure retention, and payment draft/registration separation. Final browser run additionally repairs older nested-location fixtures for the warehouse workflow endpoint and adds OLT creation and platform-owner reset coverage.
- Copy cleanup removes informal cable/splitter instructions while preserving operational consequences. OLT identity is required before advancing. Shared combobox clear actions remain alongside the field; payment bank selection uses the same label alignment as text fields.
- Final lint passed. Production build passed. Full frontend regression and final browser matrix are still running; do not merge before these results are checked. Backend password/owner integration tests remain 8 passed without subsequent backend changes.


## Release evidence

- PR: https://github.com/waduh67/qqweasdjlkasdjkwqeqwe/pull/61.
- CI at `84cd6c11`: https://github.com/waduh67/qqweasdjlkasdjkwqeqwe/actions/runs/38162090310 — all 165 frontend test files / 944 tests passed, lint/build passed, protocol tests passed. The final commit must also pass CI before merging.
- Backend: `.omo/runtime/azure-forms/password-server-tests.log` — 8 passed, 0 failed/skipped. No backend changes after this run.
- Final spacing correction enforces 20px between fields even in older inline row layouts. PSB password actions are contained in the input; generating a new password keeps it masked.
- Reproduce browser coverage: `cd web && npx playwright test --config playwright.customer-forms.config.ts`. The fixtures cover customer sections/assets/subscription nesting, own-password rejection, tenant-staff reset, platform-owner reset with expected owner ID, PSB failure/retry without credential review leakage, payment draft vs registration, OLT management steps, and nested warehouse parent-location creation. All use synthetic data; no production records or passwords are changed.
- Browser fixtures include the warehouse workflow endpoint and complete site/owner records. Required field labels include Fluent's required marker, so hidden-field assertions match the label prefix. One interrupted combined browser process was followed by separate complete browser runs for conclusive reports.
- Production verification helper: `.omo/runtime/azure-forms/verify-live.py <merge-sha>` checks public readiness, released bundle markers, activation receipt and running server/web OCI revision without reading secrets. Deploy via GitHub Actions; do not replace it with manual SSH deployment.

- Final browser acceptance: Firefox 15/15 in 42.8s, Chromium 15/15 in 41.4s; no failures, skipped tests or retries. Reports: `.omo/runtime/azure-forms/browser-{firefox,chromium}.{json,log}`. Final production build and lint passed after the spacing/password-action correction.
