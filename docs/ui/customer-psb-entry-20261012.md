# PSB entry from Customers — 12 October 2026

## Request and behavior

Remove the standalone PSB landing page and sidebar entry. Place **PSB Ekspres** next to **Tambah pelanggan** in the Customers command bar, including on mobile. Clicking it opens the existing five-step creation flow over the retained customer list. Existing `/express-psb` bookmarks redirect directly to `/customers?create=psb`, preserving query parameters.

Closing without saving keeps the search, status, pagination and customer list mounted without a fetch. Successful creation refreshes the list once and keeps the generated account credentials and installation work-order link visible. Failed saves keep the draft and review available for correction and retry. The command requires the same four write permissions as the endpoint; setup data loads only when the panel opens. Missing plans, permission failures and load retries remain available inside the same panel.

## Implementation

- `CustomersPage`: pinned PSB command, URL-controlled panel, result and list refresh ownership. List fetch dependencies exclude panel parameters; pagination correction uses the current router setter.
- `ExpressPsbPanel`: replaces `ExpressPsbFields` and the redundant landing command; preserves all five steps and the existing PSB payload.
- `useExpressPsb`: returns successful creation data to the parent, shares the endpoint permission list, initializes areas with the remaining defaults.
- `CommandBar`: optional pinned actions stay beside the primary command and out of mobile overflow.
- `ResourceForm`: initializes dirty tracking when a loading/read-only panel becomes editable, avoiding a discard prompt for an untouched form.
- `ExpressPsbPage`: compatibility redirect only. Sidebar PSB entry removed.

## Verification and recovery

Branch: `fix/customers-psb-entry-20261012`, based on main `75f46aff` (PR #61).

- Targeted DOM regressions: **18 passed**, including permission gates, lazy setup loading, legacy redirect, untouched/edited close guards, retained filters, unchanged list fetch count and pinned toolbar actions.
- Lint and production build passed. Existing lint warnings remain in unrelated code.
- Full frontend regression and Chromium/Firefox PSB browser checks are running at this checkpoint. Do not merge until they pass and final review is complete.
- Browser checks cover mobile/desktop, five steps, failed write retry, successful result, exact PSB payload, one list refresh, retained page node/focus and legacy bookmarks.
- Local evidence: `.omo/runtime/customer-psb-entry/` in `/home/fajar/ftth/ftth-azure-forms-20261012` (ignored).
- Deploy through GitHub Actions after merge. Verify public readiness and the running image revisions against the merge commit. No business records are created in production during testing.
