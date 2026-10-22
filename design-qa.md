# Unified internal Gudang UI QA (2026-10-22)

Tenant and platform consoles now share the Gudang font, navy/mint primary actions,
pill buttons, rounded fields and table frames. Creation actions sit in page headers,
including both stock opname workflows. Route-aware Fluent tokens also style portaled
dialogs, menus and notifications. Public surfaces retain their existing theme.

The local production build was captured at 375 and 1440 pixels in light and dark:
customers, catalog, users, roles, invoices, areas, VPN, BRAS, hotspot, ACS, helpdesk,
platform tenants/users/roles/VPN/RADIUS, and stock opname. The 68 route captures
and four stock editor captures are in the private, ignored evidence directory
.omo/runtime/unified-gudang-qa/, with snapshots and geometry in matrix.json.
All 72 captures were inspected through six contact sheets; stock editors were also
opened at full size. No document overflow, clipped header action or mismatched
primary button was found in this matrix. Empty data is synthetic; these captures
do not prove populated tables or backend behavior.

Customer and network browser checks passed (24 tests). Platform infrastructure
checks passed (20 tests across 375/768/1280/1920), preserving draft, password,
review, failed-write and exact-save assertions. The mobile PSB check exposed an
absolute footer covering the last coordinate action when buttons wrapped. An
in-flow footer now reserves its actual height; the existing click assertion passes
without forcing interaction. Shared theme and count tests cover public boundaries,
light/dark tokens, header placement, exact creation payload and view-only access.

Build and warehouse E2E typecheck passed. The final frontend suite passed after
the footer correction (178 files, 1030 tests, one worker). Oxlint passed with 18
existing warnings. The accent-stripe check passed
for 24 changed source files. No complete accessibility or performance conformance
claim is made. Existing specialized map layouts, compact typography and public
theme debt remain as documented in DESIGN.md.

Self-review covered route/theme boundaries, portal inheritance, responsive footer
flow, permission gates, staged changes and direct test assertions. No actionable
finding remains in the changed behavior. Production verification is recorded in
the release PR after activation.

# Resource layers and sidebar QA

The resource workflow preserves the page beneath creation/edit panels and supports nested master creation. Sidebar groups persist independently across navigation and refresh. Release details are in `docs/ui-ux/AZURE-RESOURCE-LAYERS.md`.

## Visual basis

User's Azure create reference: https://gcdnb.pbrd.co/images/pkZMMD5sB9lL.png, saved in `azure-warehouse-20260927/azure-create.png`. Prior live create/list behavior was captured before editing. Reference, before and implementation screenshots were opened together for comparison at 1910 × 921; mobile used 390 × 844. Private evidence root: `.omo/runtime/ui-ux/azure-layers-20260928/`.

The reference supplies Fluent typography, compact labeled fields, link creation below selectors, tabs, breadcrumb and fixed action footer. The latest user request additionally requires visible retained/nested panels. Desktop layers therefore keep the original page and parent edges visible; this is an interaction correction, not a claim of pixel identity to Azure's full-width VM screenshot. Existing application typography and icons remain.

## Findings resolved

- Early editor returns unmounted warehouse lists/details and caused reads/filter resets on close. Editors now render alongside their retained parent.
- Nested creation previously linked away from the receipt. Supplier, SKU and location creation now opens in a child and selects its returned record.
- Child submit/dismissal could affect an ancestor. Portal submit/click isolation and topmost close routing protect the stack; dirty confirmation applies only to the closing panel.
- Metadata loading replaced the location surface and lost the initiating control. The launcher now survives that replacement.
- Parent-location creation offered ineligible kinds. Nested options and submission enforce warehouse/bin; receipt targets enforce transit/quarantine and source code.
- Route effects previously closed all non-active sidebar groups. They now preserve other groups and restore explicit choices from browser storage.
- Final visual pass aligned inline creation text with its selector and kept the mobile action footer compact.

## Evidence and limits

643 frontend tests across 129 files passed. Firefox checks retain filtered list reads, save/select one real local supplier, navigate three layers with independent confirmation and drafts, cover four other create/close flows, and restore all five sidebar groups plus explicit collapse. Desktop/mobile captures have no page overflow or browser errors. Production validation is read-only for business data.

No actionable P1/P2 remains in the reviewed scope. This is not a complete accessibility conformance audit. Child layers are intentionally limited to the active in-memory workflow; reloading the browser does not preserve unsaved drafts. On mobile, previous layers remain mounted but are covered by the active panel. The implementation permits recursive nesting; runtime evidence exercises three layers.

Previous filter and table/create audits remain in Git at `6dd673b3:design-qa.md`, `docs/ui-ux/AZURE-FILTER-BAR.md`, and `docs/ui-ux/AZURE-RESOURCE-WORKFLOWS-RELEASE.md`.

Production verification passed for source `c68f2e82`: 11 Firefox captures and the complete layered/sidebar scenario, no page errors or business API writes. Paired before/after desktop and mobile images were opened for the final visual review. All 14 non-web containers were preserved.
