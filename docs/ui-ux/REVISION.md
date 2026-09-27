# UI/UX correction — September 2026

User rejected the complete interface after the warehouse release. Passing business regressions does not establish usability or visual acceptance. This revision covers the existing platform, tenant and customer portal interfaces.

Branch: `work/ui-ux-revision`, based on `b38d8eb9`. Edit worktree: `/home/fajar/ftth/ftth-ui-ux`.

## Evidence and direction

Fresh production desktop (1440 px) and mobile (390 px) captures are private in `.omo/runtime/ui-ux/`. Baseline tenant onboarding and VPN forms visibly clip fields. Dashboard presents repeated zeros and configuration as a metric; sidebar hides destinations by default. Email settings combine four distinct tasks into a long page. Independent source audit also identified dense technician tables, ambiguous network menu names, incomplete lists and completion controls preceding work evidence.

Keep Fluent UI, Azure blue branding, actual routes, permissions and business safeguards. Use readable navigation groups with distinct icons; expose the active destination. Use page headers with a clear action, forms grouped by purpose and panel-width responsive columns, tables with aligned headers and row actions at the end. Separate loading, failure and empty data. Progressive disclosure must retain unsaved inputs and all existing actions.

## Checkpoint 1 — saved for continuation

- Shared field containment, semantic page headings, form spacing and navigation discovery.
- Platform dashboard empty state and honest page-scoped metrics.
- Tenant onboarding grouped into identity, admin and subscription; native submission, busy state and inline error.
- VPN fields laid out responsively. Email sections separated into tabs without unmounting drafts.
- Shared table alignment, sorting indicators and constrained action column.

Not yet accepted or deployed. Remaining: tenant page families, warehouse queues/forms, technician workflow, portal, real desktop/mobile visual and keyboard checks, full frontend checks, independent review, deployment with rollback checkpoint. Existing warehouse/backend technical acceptance remains historical; it is not UI acceptance.

## Verification so far

First shared/navigation iteration: 608 tests in 117 frontend suites passed; 9 focused navigation, shell and tenant checks passed. A fresh build after the technician layout changes passed. The full-suite run overlapped those last layout edits, so repeat affected checks before release. Current screenshot review caught and corrected mobile header flex height, drawer footer width and table alignment; recapture is required. Private preview on 127.0.0.1:5174 proxies authenticated production reads and rejects non-auth writes; no business data is submitted during visual capture. Use isolated local backend for transaction regression.

## Checkpoint 2 — page workflows

- Customer list now queries all pages and server-side status filters; query/page/customer selection survive refresh in the URL. Stale responses cannot replace a newer search.
- Customer account workspace is wider; network forms use responsive field grids. Tenant navigation opens the current module and puts customer work first.
- Technician phone cards prioritize schedule and task entry; work completion follows material/evidence sections.
- Warehouse overview has compact action queues and distinct workflow shortcuts; empty queues no longer repeat zero counts.
- Notification settings separate channels, email, WhatsApp templates and triggers. Email settings retain edits between sections. Mobile command bars expose secondary actions in a menu.
- Portal and platform dashboards distinguish failed billing/tenant data from paid/empty accounts. Shared creation panels with footers use modal focus handling.

Verification: checkpoint build passed; 28 focused checks across six suites passed, including new failed/empty/partial tenant counts and unavailable portal billing. A preceding broad frontend run passed 608/117; final run still required after the latest edits. Exploratory browser capture covered 27 operator routes at desktop/mobile, then both tests failed on the test locator `Nama` not matching a required label with `*`. The form screenshot itself was intact. The locator is corrected; preserve that failed attempt in `.omo/runtime/ui-ux/exploratory-layout-01`. Some source/build changes overlapped exploration, so those images are design evidence only, not a final regression pass. Freeze product source before the next browser run.

## Checkpoint 3 — independent review corrections

Fixed invalid customer pages after deletion and refreshed the current query after pending mutations, including navigation during deletion. Onboarding accepts all nonnegative fees supported by the API and displays list-refresh failures separately from successful creation. Default mobile tables now pair each value with its label; resource grids retain readable column widths inside their scroll container. Subscription renewal controls stack on narrow screens. Portal unknown-payment copy is explicit.

18 focused tests passed, including three new behavioral regressions reproducing customer pagination/navigation and tenant creation followed by failed refresh. The first checkpoint build rejected an unsupported Testing Library `exact` type option in those new tests; removed the option (string role names are already exact). Build must pass before browser QA. Browser attempt 2 covered all 27 routes and form bounds in both sizes, then failed on Playwright's service-worker blocking initialization inside the deliberately sandboxed email preview. Its evidence is preserved under private `layout-attempt-02`. The app iframe sandbox remains intact; this visual audit now uses normal browser service-worker behavior. Added mobile subscription/control bounds and dirty-panel keyboard confirmation checks. Fresh frozen-source QA is still required. Not deployed.

Checkpoint 3 build (TypeScript and Vite) passed on the corrected tests. Lint completed with existing React effect warnings; no errors. Ten fresh platform captures (`revision-04-*`) use this source and unsaved forms only.

## Explicit Azure direction — customer reference

User explicitly reaffirmed Fluent UI with Microsoft Azure Portal layout, citing `/customers` as the reference page. Captured the current production customer route at desktop/mobile before this iteration. The shared console now uses a continuous surface, flat Azure command bars and scrolling resource grids (including previously default tables). Customer page has a resource icon/title, meaningful column widths, persistent filtering/pagination, and identity/address/service form sections. Optional map coordinates are disclosed separately. Warehouse phone cards retain a visible sort selector; keyboard focus on their semantic headers reveals those headers. Corrected the browser test to check actual resource-table scrolling for Users.

Targeted behavioral/table tests: 18 passed. Azure build 1 caught an unsupported Toolbar className; removed it, build 2 passed. Broad frontend run 1 identified the old command-bar test missing the new mobile overflow trigger; assertion updated while preserving create/delete permission checks. Repeat that affected test, and complete real local browser QA before release. All earlier failed attempts remain private evidence; none are called passing gates.

## Browser correction and compact customer grid

QA attempt 3: desktop passed; mobile completed all 27 routes, real customer creation and form bounds, then failed to reach the second dirty-confirm dialog through accessible roles although it was visible. Replaced native Modal with Fluent Dialog so confirmations and drawers share a focus manager. Preserved close/busy callbacks and all submit actions. Shared tables now draw one row separator, show a measured horizontal-scroll hint, and customer codes appear below names; full ONU placement stays in customer detail. Screenshots explicitly disable/await animations to avoid reviewing a drawer mid-transition. Attempt 3 evidence is preserved privately.

Broad frontend run before this correction: 616 tests / 121 suites passed. After this correction: 32 focused tests / 5 suites and TypeScript/Vite build passed. A fresh complete frontend run and browser attempt 4 are required/in progress. UI is still not deployed. The independent source review approved 455b76fd; review of the dialog/last table delta is pending.

## Focus restoration and final customer details

QA attempt 4 passed desktop and mobile (27 routes, real local customer creation, dirty confirmation, responsive controls and settled detail captures). Independent visual review found no blocking layout issue, then identified the API's unset-location sentinel shown as `0,0`: detail now says “Belum ditentukan” and disables map navigation. Customer status/Essentials wording now matches the Indonesian list. Asset404 was triaged to the strict area-scope authorization with a null customer area; the UI keeps the real error/retry and adds an area-assignment hint, without bypassing authorization or pretending history is empty.

Fluent imperative dialogs needed explicit focus restoration. Browser repro confirmed the defect without manual focus, and the corrected browser check now passes three cancel/reopen cycles plus discard with automatic return focus. The broad run after the initial Fluent replacement had seven failures (one stale native-modal selector and six focus/timing failures); those failures remain recorded. After correction, all 49 tests across the six affected suites pass, TypeScript/Vite build passes, and browser-spec typecheck passes. Broader screen audit expands to 44 operator routes. Fresh full frontend verification, expanded browser run and warehouse business journey remain release gates. Do not label the failed broad attempt passing.
