# Warehouse reference implementation

Branch: `feat/warehouse-reference-flow`. Reference: `/home/bayur/www/gudang`, PRD and CMS components.

## Delivery checklist

- [x] Tenant defaults, stable roles, explicit owner identity.
- [x] Platform owner details, binding and password reset with session invalidation.
- [ ] New warehouse ledger, catalog, receipts and transfers.
- [ ] Requests, quantity review, manager approval, partial receipt and immediate handover.
- [ ] Returns and snapshot-based stock counts.
- [ ] Direct WO completion, current-assignment evidence, material consumption and installation/fulfillment.
- [ ] Reference web warehouse and technician surfaces.
- [ ] Runnable Android host, authentication, photos and technician operations; shared iOS compilation.
- [ ] Legacy draining, reconciled import, readable archive and exclusive activation.
- [ ] Documentation and real API/browser/native QA evidence.

## Invariants

Default provisioning adds missing data without replacing custom names, grants, assignments or locations. Owner has full tenant access and no platform privilege. Production receives no example users or stock.

Requests are independent of WO. Approval does not reserve stock. Admin handover credits technician stock immediately. Return receipt and counts revalidate the current ledger. Quantities retain integer EA/MM semantics. Stock cannot become negative, identities cannot be reused, and idempotency keys cannot change payload.

New WO completes after evidence and material validation without human completion review. Existing integration semantics remain source-aware. Old stock transactions must finish before each tenant activates the new writer; history remains readable.

## Verification ledger

Record each behavior commit and its executed checks below. UI and native evidence must come from the real application/API, and historical database safeguards must remain tested.

- Tenant defaults: `bash scripts/warehouse/qa.sh server --tests com.duluin.ftth.TenantDefaultsIT --tests com.duluin.ftth.tenancy.DeleteTenantIT --tests com.duluin.ftth.IamEndToEndIT --tests com.duluin.ftth.SelfSignupIT --tests com.duluin.ftth.inventory.WarehousePolicyITRoleAuthority --no-parallel` passed 22 tests, zero failures/skips on 2026-10-08. Covers idempotent provisioning, custom-role preservation, suspended tenants, owner tenant isolation, full tenant authority without platform access, and default-only warehouse deletion via empty-tenant cascade.
- Platform owner: backend `f752a2c7` passed 24 tests across TenantOwnerIT, CredentialJwtDecoderTest, IamEndToEndIT, TwoFactorLoginIT and TenantDefaultsIT. Owner binding and password resets invalidate access/refresh credentials under the tenant IAM fence; concurrent owner replacement rejects a stale reset. No password enters the audit payload.
- Platform owner UI: production build and real API/browser scenarios passed on 2026-10-08 at 1280x900 and 375x812. Light/dark captures cover stacked owner identity, candidate pagination, selection across empty search, retained validation drafts, Cancel/Escape/X reset and successful mutations. Old sessions and passwords fail after reset; the new password logs in. Evidence: `.omo/runtime/warehouse-playwright.json` and `.omo/runtime/warehouse-playwright-artifacts/tenant-owner-platform-chan-d5bd0-mmediate-session-revocation-warehouse-{desktop,mobile}/`. Self-review inspected fresh captures and the implementation; independent reviewer routing was unavailable.
