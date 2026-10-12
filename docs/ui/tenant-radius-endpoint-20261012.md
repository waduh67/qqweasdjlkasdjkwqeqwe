# Tenant RADIUS allocation — 12 October 2026

## Problem and correction

The demo tenant could not see the platform RADIUS node in BRAS or the RouterOS guide. Tencent FTTH had a limit of two tenants, already occupied. The endpoint returned HTTP 409, but the page swallowed the error and displayed an unconfigured-server message.

The production node now allows four tenants, matching the four currently registered tenants. All four, including `pt-demo` (PT. Demo Abadi Selamanya), have a persisted assignment. The node's database connection check passed. Host, ports, credentials and existing assignments were preserved. Accounting polling allocates nodes for active tenants, so capacity must include those tenants even before they open BRAS.

The endpoint also ran allocation inside a read-only transaction. PostgreSQL integration tests reproduced a successful response without a saved assignment. `NasService.radiusEndpoint` now uses a write transaction, making the first allocation persist and subsequent reads reuse it.

BRAS and its nested RouterOS guide distinguish loading, unavailable capacity, request failures and genuinely missing configuration. **Coba lagi** reloads the endpoint without closing the guide or clearing the BRAS draft. **Segarkan** also reloads RADIUS data. Capacity messages give a short explanation and direct tenant users to the platform administrator.

## Verification

- Before the transaction correction: two of three new integration tests failed because assignments were not persisted.
- After correction: **26 backend tests passed**, covering endpoint allocation, capacity recovery, unavailable nodes, allocation policy, NAS client synchronization and platform node management.
- **25 frontend tests passed**, including endpoint loading, failed allocation, retry, nested guide retention, refresh and VPN target selection.
- **8 Chromium/Firefox browser scenarios passed**, covering BRAS creation and capacity recovery at mobile and desktop widths. Retried RouterOS configuration targets the RADIUS gateway `10.208.67.1` for a router on `10.208.67.0/24`; the draft and shared secret remain intact.
- Lint and production build passed. Existing unrelated lint warnings remain.
- Production API verification: Tencent FTTH active, limit 4, assigned tenants 4, demo assignment present; RADIUS database connection PASS. No BRAS or customer records were created for this verification.

## Release and recovery

Branch: `fix/tenant-radius-endpoint-20261012`, based on main `62b030ec` (PR #62). Merge after CI passes, then deploy through GitHub Actions. The merged commit and successful production deploy workflow are the release authority.

Local evidence is under `.omo/runtime/radius-endpoint/` in `/home/fajar/ftth/ftth-azure-forms-20261012` (ignored). Private node snapshots and authenticated sessions are mode 0600 and must not be committed. Sanitized capacity and assignment proofs, backend/frontend logs and browser captures are stored separately in the same directory.

If deployment fails, resume from the branch/PR and inspect the Actions run before retrying. The live capacity adjustment is already complete and does not require repeating. Verify public readiness, deployed image revisions and the BRAS bundle after deployment. New tenant growth beyond four requires another capacity review or an additional RADIUS node.
