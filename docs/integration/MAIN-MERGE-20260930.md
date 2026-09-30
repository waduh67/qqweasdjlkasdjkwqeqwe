# Main integration — 2026-09-30

Source histories: main bf93bccd and work/azure-resource-workflows af211d76. Integration worktree: /home/fajar/ftth/ftth-main-integration, branch work/main-integration-20260930. Private logs: .omo/runtime/main-integration-20260930/.

Merge checkpoint 7b0b3ec5 preserves both histories and resolves the 11 textual conflicts. The follow-up candidate includes reviewed deployment activation and Fluent modal activation after Tabster registration. It is not yet the final main release. Final frontend/browser and focused server integration results are pending; do not deploy this intermediate checkpoint.

Decisions:
- Preserve all main RADIUS, billing callback/idempotency, WhatsApp draft/test/URL-tab, SNMP/inventory and single-flight features; retain warehouse and Fluent workflows.
- Warehouse V173 is already applied in the authorized Azure database, through V178.12. The independent main RADIUS V173 was not applied there; rename it to V179 without changing existing warehouse migrations. Other databases that applied the old RADIUS V173 need an explicit migration-history reconciliation before upgrade; never run Flyway repair blindly.
- Keep one alarm-change announcement from the polling transaction while retaining observation provenance and acquisition-window validation.
- Keep the full warehouse CI prerequisite and exact tested image publication. Merge Cloudflare SSH transport while preserving the installed Azure shared-proxy and image-pin wrapper.
- Production configuration defaults true; explicit host overrides remain supported.

Candidate checks completed: all server test sources compile; frontend production build, lint (existing warnings), warehouse E2E typecheck; 367 unchanged warehouse migration files and 368 unique migration versions; 5 activation/rollback tests and 6 workflow gate tests; both native and shared-proxy Compose configuration validation. Independent read-only review closed the deployment and modal activation findings.

Deployment uses the installed wrapper when present, preserving non-app container identities. Only the tested immutable server/web images are activated; failures restore original image pins unless an operator changed them. SSH/configuration failures stop topology detection. Main's native Nginx deployment remains available only after explicit topology detection.

No production database mutation or manual application deployment is part of this integration verification. Main push will run the repository's configured CI/deploy workflow.
