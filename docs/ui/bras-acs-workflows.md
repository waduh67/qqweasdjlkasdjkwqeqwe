# BRAS and ACS workflows

BRAS uses a retained resource panel with separate Basics, RADIUS, RouterOS access,
service areas, and review steps. RouterOS access is shown only for MikroTik. Next
and tab navigation validate preceding steps; review never writes. A rejected save
keeps the review and draft, while an in-flight save blocks duplicate submission.
The RouterOS configuration guide opens as a nested panel and restores focus on close.
Editing preserves the collector assignment, existing secrets, and area assignments
that the operator cannot list.

Both resource lists place commands immediately above search and automatic filter
chips. BRAS exposes create at the left. ACS separates Overview, Devices, ONT
settings, and Activity. Device filters survive tab changes and apply to CSV export.
The fleet update stays on Overview because its scope is independent of list filters;
its result includes queued, failed, and skipped devices. Device and activity tables
use single-line text cells. ONT settings retain the labels found on routers, group
connection and reporting values, and keep passwords concealed until requested.

Permissions remain separate: ACS settings-only users do not fetch fleet statistics,
devices, or activity. Failed loads offer retry; stale filter responses cannot replace
a newer device result.

## Verification

- Component behavior: `npm --prefix web test -- src/pages/BngPage.test.tsx src/pages/AcsPage.test.tsx src/components/organisms/CreationReview.test.tsx src/utils/radiusTarget.test.ts`
- Production build: `npm --prefix web run build`
- Browser behavior and layout: run `npx playwright test --config playwright.network.config.ts` from `web` after building. Covers Chromium and Firefox at 375 and 1280 px using local mocked APIs, with screenshots and traces under `.omo/runtime/network-ui`.
- Release through the existing GitHub Actions verification and deployment workflows.

Verification 2026-10-11: 928 web tests passed. After the final focus and column
ordering adjustment, the 13 affected component tests, production build, and all
eight Chromium/Firefox browser scenarios passed again. Lint reports only existing
warnings in unrelated files. Release is tracked by PR #56.

The public domain returned HTTP 530 / Cloudflare 1033 before release. The previous
main deployment (run 38045936436) also failed image smoke verification before its
deploy step. These release conditions must be resolved before claiming the UI is
live; the interface checks above use local mocked APIs.
