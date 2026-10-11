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
- [x] Account/user/tenant password interfaces: 6 focused UI tests passed; browser verification remains pending.
- [ ] Customer detail layout and tab workflows.
- [ ] Shared form spacing and grouped-step support.
- [ ] All creation entry points reviewed; complex forms grouped explicitly.
- [ ] Desktop/mobile Firefox and Chromium verification.
- [ ] Full frontend checks and relevant backend checks.
- [ ] Merge, deploy, and verify production.

## Source inventory

Panel counts include detail/edit panels; each file must be inspected to identify its creation flows. Field counts are an initial heuristic and exclude fields in child components. Login is outside the create-resource audit; signup and portal ticket creation are included. Existing safe flows should be preserved, including warehouse command identity/replay locks.

| Source | Panels | Direct fields | Review |
|---|---:|---:|---|
| `web/src/pages/AccountSecurityPage.tsx` | 1 | 2 | Pending |
| `web/src/pages/AreasPage.tsx` | 1 | 2 | Pending |
| `web/src/pages/BngConfiguration.tsx` | 1 | 1 | Pending |
| `web/src/pages/CatalogPage.tsx` | 1 | 26 | Pending |
| `web/src/pages/CustomerDetailPage.tsx` | 3 | 13 | Pending |
| `web/src/pages/CustomersPage.tsx` | 1 | 8 | Pending |
| `web/src/pages/HotspotPage.tsx` | 3 | 10 | Pending |
| `web/src/pages/InventoryPage.tsx` | 8 | 25 | Pending |
| `web/src/pages/InvoicesPage.tsx` | 6 | 6 | Pending |
| `web/src/pages/MapPage.tsx` | 3 | 0 | Pending |
| `web/src/pages/MonitoringPage.tsx` | 1 | 1 | Pending |
| `web/src/pages/MyMaterialReceipt.tsx` | 1 | 3 | Pending |
| `web/src/pages/MyMaterialResidualAcknowledgement.tsx` | 1 | 1 | Pending |
| `web/src/pages/MyMaterialReturn.tsx` | 1 | 3 | Pending |
| `web/src/pages/MyMaterialRma.tsx` | 1 | 1 | Pending |
| `web/src/pages/OltDetailPage.tsx` | 2 | 17 | Pending |
| `web/src/pages/PaymentGatewaySettingsPage.tsx` | 2 | 21 | Pending |
| `web/src/pages/PlatformAcsSettingsPage.tsx` | 0 | 4 | Pending |
| `web/src/pages/PlatformEmailSettingsPage.tsx` | 1 | 7 | Pending |
| `web/src/pages/RadiusServersPage.tsx` | 1 | 12 | Pending |
| `web/src/pages/RolesPage.tsx` | 1 | 2 | Pending |
| `web/src/pages/SignupPage.tsx` | 0 | 4 | Pending |
| `web/src/pages/TaxSettingsPage.tsx` | 1 | 1 | Pending |
| `web/src/pages/TenantsPage.tsx` | 1 | 1 | Pending |
| `web/src/pages/UsersPage.tsx` | 2 | 5 | Pending |
| `web/src/pages/VpnCredentialsBlade.tsx` | 1 | 3 | Pending |
| `web/src/pages/VpnPage.tsx` | 5 | 9 | Pending |
| `web/src/pages/VpnServersPage.tsx` | 1 | 6 | Pending |
| `web/src/pages/WorkOrdersPage.tsx` | 1 | 10 | Pending |
| `web/src/pages/b2b/B2BClientsPage.tsx` | 1 | 7 | Pending |
| `web/src/pages/b2b/B2BVisitsPage.tsx` | 1 | 3 | Pending |
| `web/src/pages/b2b/shared.tsx` | 2 | 0 | Pending |
| `web/src/pages/helpdesk/TicketBlade.tsx` | 2 | 5 | Pending |
| `web/src/pages/warehouse/MaterialPlanEditor.tsx` | 1 | 5 | Pending |
| `web/src/pages/warehouse/ReferenceCountEditor.tsx` | 2 | 4 | Pending |
| `web/src/pages/warehouse/ReferenceReceiptEditor.tsx` | 1 | 4 | Pending |
| `web/src/pages/warehouse/ReferenceRequestEditor.tsx` | 1 | 9 | Pending |
| `web/src/pages/warehouse/ReferenceRequestMovements.tsx` | 3 | 7 | Pending |
| `web/src/pages/warehouse/ReferenceRequestReview.tsx` | 2 | 3 | Pending |
| `web/src/pages/warehouse/ReferenceReturnEditor.tsx` | 2 | 4 | Pending |
| `web/src/pages/warehouse/ReferenceSettingsPage.tsx` | 1 | 2 | Pending |
| `web/src/pages/warehouse/ReferenceTransferEditor.tsx` | 1 | 5 | Pending |
| `web/src/pages/warehouse/WarehouseActivationEditor.tsx` | 1 | 1 | Pending |
| `web/src/pages/warehouse/WarehouseAllocationEditor.tsx` | 1 | 3 | Pending |
| `web/src/pages/warehouse/WarehouseApprovalsPage.tsx` | 1 | 1 | Pending |
| `web/src/pages/warehouse/WarehouseCountEditor.tsx` | 1 | 5 | Pending |
| `web/src/pages/warehouse/WarehouseCountsPage.tsx` | 1 | 2 | Pending |
| `web/src/pages/warehouse/WarehouseCustomerRma.tsx` | 1 | 6 | Pending |
| `web/src/pages/warehouse/WarehouseDelegationForm.tsx` | 1 | 6 | Pending |
| `web/src/pages/warehouse/WarehouseDispositionForm.tsx` | 1 | 4 | Pending |
| `web/src/pages/warehouse/WarehouseDrainDialog.tsx` | 1 | 0 | Pending |
| `web/src/pages/warehouse/WarehouseLocationEditor.tsx` | 1 | 8 | Pending |
| `web/src/pages/warehouse/WarehousePendingMaterialReturns.tsx` | 1 | 0 | Pending |
| `web/src/pages/warehouse/WarehouseProvenanceCase.tsx` | 3 | 8 | Pending |
| `web/src/pages/warehouse/WarehouseProvenanceOpening.tsx` | 4 | 5 | Pending |
| `web/src/pages/warehouse/WarehouseProvenancePage.tsx` | 1 | 0 | Pending |
| `web/src/pages/warehouse/WarehouseReceiptActions.tsx` | 1 | 7 | Pending |
| `web/src/pages/warehouse/WarehouseReceiptEditor.tsx` | 1 | 15 | Pending |
| `web/src/pages/warehouse/WarehouseReceiptEvidence.tsx` | 1 | 0 | Pending |
| `web/src/pages/warehouse/WarehouseReplenishmentRuleForm.tsx` | 1 | 3 | Pending |
| `web/src/pages/warehouse/WarehouseReservationEditor.tsx` | 1 | 3 | Pending |
| `web/src/pages/warehouse/WarehouseReturnActions.tsx` | 1 | 9 | Pending |
| `web/src/pages/warehouse/WarehouseReturnEditor.tsx` | 4 | 3 | Pending |
| `web/src/pages/warehouse/WarehouseReturnReacquisition.tsx` | 1 | 3 | Pending |
| `web/src/pages/warehouse/WarehouseReturnsPage.tsx` | 1 | 0 | Pending |
| `web/src/pages/warehouse/WarehouseSettingsPage.tsx` | 1 | 3 | Pending |
| `web/src/pages/warehouse/WarehouseSkuEditor.tsx` | 1 | 8 | Pending |
| `web/src/pages/warehouse/WarehouseSupplierEditor.tsx` | 1 | 3 | Pending |
| `web/src/pages/warehouse/WarehouseSupplierReplacement.tsx` | 1 | 8 | Pending |
| `web/src/pages/warehouse/WarehouseTransferActions.tsx` | 1 | 5 | Pending |
| `web/src/pages/warehouse/WarehouseTransferEditor.tsx` | 1 | 7 | Pending |
| `web/src/pages/workorder/ReferenceWorkAssignment.tsx` | 1 | 1 | Pending |
| `web/src/pages/workorder/ReferenceWorkDispatch.tsx` | 2 | 4 | Pending |
| `web/src/pages/workorder/ReferenceWorkEditor.tsx` | 2 | 8 | Pending |
| `web/src/pages/workorder/ReferenceWorkTypesPage.tsx` | 1 | 6 | Pending |
| `web/src/components/organisms/AccessNodeDetail.tsx` | 1 | 12 | Pending |
| `web/src/components/organisms/OdfDetail.tsx` | 1 | 5 | Pending |
| `web/src/components/organisms/PortalCredentialCard.tsx` | 1 | 2 | Pending |
| `web/src/components/organisms/ReleaseDropDialog.tsx` | 1 | 2 | Pending |
| `web/src/components/organisms/SplicingManager.tsx` | 1 | 8 | Pending |
| `web/src/components/organisms/SplitterPanel.tsx` | 1 | 2 | Pending |
| `web/src/components/organisms/TenantOwnerPanel.tsx` | 1 | 0 | Pending |
| `web/src/components/organisms/TenantOwnerPasswordForm.tsx` | 0 | 2 | Pending |
| `web/src/components/organisms/TenantSubscriptionModal.tsx` | 1 | 5 | Pending |
| `web/src/components/organisms/WhatsAppTemplateCard.tsx` | 1 | 5 | Pending |
| `web/src/components/organisms/customer/CustomerAssetAction.tsx` | 1 | 1 | Pending |
| `web/src/components/organisms/customer/CustomerAssetException.tsx` | 1 | 2 | Pending |
| `web/src/components/organisms/customer/CustomerAssetInstallation.tsx` | 1 | 3 | Pending |
| `web/src/components/organisms/map/CablePanel.tsx` | 1 | 1 | Pending |
| `web/src/components/organisms/map/OtdrSection.tsx` | 1 | 5 | Pending |
| `web/src/components/organisms/map/PlaceAssetForm.tsx` | 1 | 21 | Pending |
| `web/src/components/organisms/map/SaveCablePanel.tsx` | 1 | 5 | Pending |
| `web/src/components/organisms/provisioning/ProvisioningEditors.tsx` | 1 | 10 | Pending |
| `web/src/components/organisms/provisioning/ProvisioningEvidencePanels.tsx` | 1 | 1 | Pending |
| `web/src/components/organisms/warehouse/WarehouseCommandDialog.tsx` | 1 | 0 | Pending |
| `web/src/components/organisms/workorder/WorkOrderMaterialHandover.tsx` | 1 | 4 | Pending |
| `web/src/components/organisms/workorder/WorkOrderMaterialRework.tsx` | 1 | 3 | Pending |
| `web/src/components/organisms/workorder/WorkOrderMaterialUse.tsx` | 1 | 3 | Pending |
| `web/src/components/organisms/workorder/WorkOrderMaterials.tsx` | 0 | 1 | Pending |
| `web/src/components/organisms/workorder/WorkOrderSignatureUpload.tsx` | 0 | 2 | Pending |
| `web/src/portal/PortalForgotPasswordPage.tsx` | 0 | 4 | Pending |
| `web/src/portal/PortalHelpTab.tsx` | 0 | 4 | Pending |
| `web/src/portal/PortalProfilPage.tsx` | 0 | 4 | Pending |

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
