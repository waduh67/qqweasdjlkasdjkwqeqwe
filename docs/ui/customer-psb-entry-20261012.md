# PSB entry from Customers — 12 October 2026

## Request and behavior

Remove the standalone PSB landing page and sidebar entry. Place **PSB Ekspres** next to **Tambah pelanggan** in the Customers command bar, including on mobile. Clicking it opens the existing five-step creation flow over the retained customer list. Existing `/express-psb` bookmarks redirect directly to `/customers?create=psb`, preserving query parameters.

Closing without saving keeps the search, status, pagination and customer list mounted without a fetch. Successful creation refreshes the list once and keeps the generated account credentials and installation work-order link visible. Failed saves keep the draft and review available for correction and retry. The command requires the same four write permissions as the endpoint; setup data loads only when the panel opens. Missing plans, permission failures and load retries remain available inside the same panel.

## Implementation

- `CustomersPage`: pinned PSB command, URL-controlled panel, result and list refresh ownership. List fetch dependencies exclude panel parameters; pagination correction uses the current router setter.
- `ExpressPsbPanel`: replaces `ExpressPsbFields` and the redundant landing command; preserves all five steps and the existing PSB payload.
- `useExpressPsb`: returns successful creation data to the parent, shares the endpoint permission list, initializes areas with the remaining defaults.
- `CommandBar`: optional pinned actions stay beside the primary command and out of mobile overflow.
- `ResourceForm`: accepts a domain dirty check for forms with lazy controls. PSB compares its initialized draft, plan, BRAS, service type and password; loading a map or toggling password visibility does not count as an edit.
- `ExpressPsbPage`: compatibility redirect only. Sidebar PSB entry removed.

## Verification and recovery

Branch: `fix/customers-psb-entry-20261012`, based on main `75f46aff` (PR #61).

- Targeted DOM regressions: **18 passed**, including permission gates, lazy setup loading, legacy redirect, untouched/edited close guards, retained filters, unchanged list fetch count and pinned toolbar actions.
- Lint and production build passed. Existing lint warnings remain in unrelated code.
- **952 frontend tests passed** across 165 files. **12 Chromium/Firefox PSB browser scenarios passed** across desktop and mobile (8 creation/navigation cases and 4 legacy empty-plan cases). Final targeted DOM regression: **18 passed**. Merge after CI is green for the final commit.
- Browser checks cover mobile/desktop, five steps, failed write retry, successful result, exact PSB payload, one list refresh, retained page node/focus and legacy bookmarks.
- Local evidence: `.omo/runtime/customer-psb-entry/` in `/home/fajar/ftth/ftth-azure-forms-20261012` (ignored).
- Deploy through GitHub Actions after merge. Verify public readiness and the running image revisions against the merge commit. No business records are created in production during testing.

### Final review

- Browser checks exposed an untouched-form discard prompt when the lazy map mounted. Dirty tracking now compares PSB data instead of the rendered input inventory; all eight browser cases passed after the fix.
- UI screenshots inspected at 375px and 1440px: both create commands remain visible, filters stay immediately below the command bar, and the PSB sidebar item is absent.
- Warehouse E2E TypeScript check passed. The real-server warehouse intake fixture now enters PSB through Customers and follows all five steps; its full backend workflow was not rerun for this frontend-only change.
- PR: https://github.com/waduh67/qqweasdjlkasdjkwqeqwe/pull/62. The merged commit and successful production deploy workflow are the release authority.
