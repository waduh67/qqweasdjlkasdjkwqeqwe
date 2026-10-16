# Existing subscriber migration — implementation checkpoint

Branch: `feat/customer-migration-20261016`, based on main `a22041fd`.

Approved behavior: distinguish PSB/new installation from existing subscriber migration. Manual, CSV and MikroTik migration preserve ACTIVE/SUSPENDED service state, optional installation date and coordinates, negotiated price and explicit first invoice date. Recording a migration does not activate RADIUS or create a work order. Separate customer detail actions start billing and prepare RADIUS.

Implemented at this checkpoint:
- Subscription billing boundary and imported state initialization without activation events.
- Tenant-scoped encrypted migration metadata, replay checks and source identity deduplication.
- Shared migration registration for manual, CSV and MikroTik; per-row import transactions.
- Grouped migration resource blade, entry from Customers, old import URL redirects, customer detail billing/network actions.
- Follow-up screenshot RixusMDXK9xn: compact map-panel command bar; primary/detail and cable remain visible, remaining actions in an overflow menu.

Validation so far: production Kotlin compilation, web production build, 11 import mapping unit tests passed. Integration, browser checks, final code review and release are still pending. This checkpoint is not a release.

Remaining work:
1. Integration tests for replay, tenant/area permission boundaries, billing dates, no network/WO side effects, encrypted credentials and explicit RADIUS preparation.
2. Align historical import compatibility tests with new passive migration behavior and per-row commits.
3. Browser tests and screenshots for the grouped forms and map panel toolbar; fix any issues found.
4. Check audit/permission/date semantics, retry recovery and customer export compatibility; polish copy and remove stale import comments.
5. PR, CI, merge main, deploy through GitHub Actions and verify exact live image revisions.

Private evidence and recovery plan: `.omo/runtime/customer-migration/` (not committed). No credentials or customer exports belong in committed evidence.
