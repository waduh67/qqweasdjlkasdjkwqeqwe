# Main integration — 2026-09-30

Source histories: main bf93bccd and work/azure-resource-workflows af211d76. Integration worktree: /home/fajar/ftth/ftth-main-integration, branch work/main-integration-20260930. Private logs: .omo/runtime/main-integration-20260930/.

Merge checkpoint 7b0b3ec5 preserves both histories and resolves the 11 textual conflicts. Follow-up commits include reviewed deployment activation and Fluent modal activation after Tabster registration. Integration verification is complete and the result is ready to become main. New contributors should use main; the integration branch remains as a recovery checkpoint.

Decisions:
- Preserve all main RADIUS, billing callback/idempotency, WhatsApp draft/test/URL-tab, SNMP/inventory and single-flight features; retain warehouse and Fluent workflows.
- Warehouse V173 is already applied in the authorized Azure database, through V178.12. The independent main RADIUS V173 was not applied there; rename it to V179 without changing existing warehouse migrations. Other databases that applied the old RADIUS V173 need an explicit migration-history reconciliation before upgrade; never run Flyway repair blindly.
- Keep one alarm-change announcement from the polling transaction while retaining observation provenance and acquisition-window validation.
- Keep the full warehouse CI prerequisite and exact tested image publication. Merge Cloudflare SSH transport while preserving the installed Azure shared-proxy and image-pin wrapper.
- Production configuration defaults true; explicit host overrides remain supported.

Checks completed: all server test sources compile; 761 frontend tests; 16 Firefox browser tests covering OLT inventory/manual polling, map refresh, and nested warehouse forms at mobile/desktop sizes; frontend production build, lint (existing warnings), warehouse E2E typecheck; 367 unchanged warehouse migration files and 368 unique migration versions; 5 activation/rollback tests and 6 workflow gate tests; both native and shared-proxy Compose configuration validation. Independent read-only review closed the deployment and modal activation findings. Firefox map checks use Xvfb and software WebGL; this VPS's headless Firefox cannot create a WebGL2 context.

The focused server batch ran 720 cases across RADIUS, billing, monitoring/provenance, warehouse schema/RLS, WhatsApp settings and platform callbacks. 719 passed initially; one test helper incorrectly counted both tenants together. After correcting the helper, all 20 manual-polling and controller cases passed on rerun. No other server cases failed or were skipped. Contract (2), SNMP (30), and collector (170) tests also passed. The complete native, historical-upgrade and warehouse release matrix remains mandatory in GitHub CI before deployment.

Deployment uses the installed wrapper when present, preserving non-app container identities. Only the tested immutable server/web images are activated; failures restore original image pins unless an operator changed them. SSH/configuration failures stop topology detection. Main's native Nginx deployment remains available only after explicit topology detection.

No production database mutation or manual application deployment is part of this integration verification. Main push will run the repository's configured CI/deploy workflow.

Recovery: checkpoints are pushed to work/main-integration-20260930. A full Git bundle, this handoff, test reports and verification receipts are retained privately under /opt/ftth/setup-private/main-merge-20260930/<final-commit>/. The local resume cursor's mainIntegration entry records the exact source commit, backup checksum and CI state without replacing earlier warehouse acceptance records.
