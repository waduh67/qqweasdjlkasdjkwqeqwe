# NetOps Console Design System


## Internal Gudang standard

All authenticated tenant and platform pages follow
`/var/www/html/gudang/resources/css/app.css` and its CMS sidebar/dashboard.
This standard includes customer, billing, network, monitoring, IAM, infrastructure,
and every internal dialog, drawer, menu and toast. Login, signup, customer and
hotspot portals, public invoices and payment return pages keep their public theme.
Route-aware Fluent and CSS tokens share this boundary, including portaled surfaces.
Primary actions use navy #161c28 with white ink in light mode and mint #00e29d
with navy ink in dark mode. Hover uses #293344/light and #6bf1c5/dark; pressed
uses #080e1a/light and #00c88b/dark. Links and focus use AA green #007d58/light.
Shared buttons have a 40px target, 14px semibold label, 16px inline padding and
pill radius. Form controls use 14px text, 40px minimum height and 14px radius.
Cards and table frames use 24px radii, neutral borders and 20px padding (tables
remain flush). Tables use 40px minimum rows, 12px cell padding, tonal selection,
and retain sorting, selection and labelled mobile records. Header actions sit
opposite the title on desktop and wrap below it on phones. Toolbars wrap with
8px gaps; tabs use pills. Existing map/split-panel and resource scroll owners stay.
Both count workflows use the shared plus icon and “Tambah stock opname” action
in the page header, gated by their existing manage permission; refresh stays
secondary. Reference adjustments and legacy blind counts remain separate.
`.gudang-shell` owns navy navigation #161c28 (dark #080e1a), mint #00e29d
active icons and tonal selection, with a neutral header. `.operational-content`
uses canvas #f8f9fb, white cards, border #e2e8ed, ink #161c28, secondary
#414b5c, muted #626d7b and AA interaction ink #007d58. Dark surfaces are
#080e1a/#121824/#222c3d with light ink. Body uses local Hanken Grotesk and
headings Space Grotesk, with system fallbacks. Cards have 24px radii,
20–24px padding and 16px gaps; tabs are pills with tonal selected fill.
Existing Fluent forms, exact quantities and resource review remain.

Menu order follows tasks: Dashboard, KPI, Gudang or Material Saya and Retur,
Request Material, Pelanggan, WO, Kunjungan Saya, Daily, B2B, then setup and
management. Catalog links select Barang, Gudang & Rak or Pemasok by query tab.
Permissions determine entries and actionable request counts, never account names.
Client B2B, Manajemen IP, Vendor Jaringan, Rekap B2B and Visit B2B share the
B2B navigation group, in that order. Network inventory lists use the shared
`FilterBar`, `FilterSearch`, `FilterSelect` and `FilterPicker` primitives: one
compact row on wide screens that wraps within the content width on phones.
Vendor search and pagination stay inside the picker popover; the list toolbar
does not repeat the vendor search as a separate form field.
Counts refresh after successful commands and every minute while visible;
failed reads show an unavailable indicator rather than a false zero.
Ordinary visits display WO/customer/area, real session times, active/history,
pagination and current-assignment actions. B2B stays separate. Dashboard totals
use the entire authorized scope. KPI shows actual/target/percentage and keeps
four equal weights; team viewers alone select another technician.
At 375px content reflows; focus rings, textual states, 40px navigation targets
and reduced-motion behavior remain. No accent edge stripes or new accessibility
debt are accepted.


## 1. Atmosphere & Identity

The internal contract above governs tokens and controls on tenant and platform pages. Public screens retain their existing theme. Cards and table frames use 24px radii, body text uses local Hanken Grotesk, headings use local Space Grotesk, and primary actions use navy in light mode and mint in dark mode. Existing resource behavior and specialized layout rules below still apply.

## 2. Color

Internal colors are defined in `operational.css`, `internal-controls.css` and `theme/gudangTheme.ts`: navy #161c28 or mint #00e29d primary actions, AA green #007d58 light links and focus, canvas #f8f9fb/#080e1a, surface #ffffff/#121824 and neutral border #e2e8ed/#222c3d. Status colors retain their semantic tokens and textual labels.

## 3. Typography

- Primary and numeric text: local Hanken Grotesk with system fallbacks.
- Headings: local Space Grotesk.
- Page title: 28px/36px at 600; section title: 20px/28px at 600.
- Body: 14px/20px; metadata can keep its existing density.
- IDs and hashes wrap where needed.

## 4. Spacing & Layout

- Base rhythm: 4px, expressed through the existing `0.25rem`, `0.5rem`, `0.75rem`, `1rem`, `1.25rem`, `1.5rem`, and `2rem` steps.
- Full-width operator pages use `.stack`; settings-only pages use `.settings-page`.
- Responsive grids use `repeat(auto-fit, minmax(min(..., 100%), 1fr))` so primary content reflows without horizontal scrolling at 375px.
- The application shell owns document scrolling. Tabs may scroll horizontally using the existing `[role='tablist']` rule.

### Platform infrastructure forms
- VPN, RADIUS, billing and ACS use the scoped `.horizontal-form` primitive: one complete field per row, a 220px label column and a flexible control column with a 20px gap.
- Containers below 760px stack labels above controls; controls and hints wrap within the available width. Resource blades retain their existing scroll and review behavior.
- Billing sections fill the platform workspace. Other settings pages keep their existing width.
- Labels remain programmatically associated with controls, errors retain drafts, saves disable repeated submission, and closing a dirty resource form requires confirmation.
- Platform ACS distinguishes application API connectivity from ONT Inform connectivity. Passwords are write-only; an empty replacement preserves the saved password. No new visual tokens or accepted accessibility debt.

### Tenant owner administration
- Tenant rows show owner name, email and account status in a stacked cell with the existing 4px rhythm. Owner cells wrap long identities and grow the row height; mobile users can scroll the table to these columns. Unresolved historical owners are explicitly Belum ditentukan. The platform tenant has no owner controls.
- The owner panel uses the shared Blade. Its body owns scrolling, following StyleGallery scroll-body-shell; header and footer stay visible. Existing tokens and single-column fields apply at desktop and phone widths.
- Simpan owner is the primary action. Password reset uses a secondary button within its own labeled form. The shared Blade exposes closeDisabled so its X and dismiss interactions show the same pending state as Batal.
- Owner selection uses a labeled search and paginated candidate list. Selection stays visible across pages. Inactive candidates cannot be selected. Binding requires an explicit save; password reset names the current owner and requires matching passwords of at least eight characters.
- Validation keeps inputs. Batal, Escape and X discard the panel when no save is pending. During a save, dismissal, owner selection and both mutations are disabled to prevent conflicting operations. Successful save closes the panel and reloads tenant data. Reset clears password fields and reports that old sessions ended. A changed owner requires refreshing identity before another reset. Loading, errors, empty candidates and unbound owners have visible text. Shared Fluent primitives provide keyboard and screen reader behavior. No new accessibility debt.

### Inventory resource forms
- Field groups in resource flyouts use one complete field per row. Nested grid wrappers and narrow port/version wrappers fill the available width so the existing label and control columns stay readable.
- Labels stack above controls in narrow flyouts. Identity, SNMP and Web Management fields retain their source order, and the existing flyout body owns scrolling above the review footer.

### Tenant dashboard and map
- The tenant dashboard fills the workspace. Recent alarms and daily actions occupy separate full-width rows so an empty alarm panel stays compact. Daily actions reflow into columns, with each title above its description.

## 5. Components

### B2B clients and monthly visits
- Client B2B uses the warehouse presentation of DataTable, explicit search, pagination and shared ResourceForm review. Each client shows the frozen current assignment and the settings for next month. Assigned NE selection has search, pagination and a retained selection.
- Rekap B2B and Visit B2B use a labeled month control, target/count/remaining columns and clipped Monday-Sunday week details. Missed weeks have text; extra same-day visits remain visible in history. Current active assignments expose the report action.
- A report requires notes and one to five JPEG/PNG files. Shared command review retains the command and photo bytes during uncertain retries. Private photos load only in the opened visit detail and object URLs are released on close. Loading, empty, error, read-only, conflict, pending and retry states have visible Indonesian labels.
- Existing spacing, colors, responsive table labels and form scroll ownership apply. Buttons, fields, review and dialogs remain keyboard operable. No new visual tokens or accessibility debt.
- Breadcrumbs use Lapangan and the visible B2B page names. The Lapangan crumb opens the first B2B page allowed by the current account. A failed request shows its reload action without also claiming that the dataset is empty.

### Card
- Structure: `.card`, optionally `.card-head` and `.card-body`.
- States: static by default; `.clickable` supplies hover, active, and focus states.
- Accessibility: interactive cards must be real buttons or links.

### Status badge
- Structure: shared `Badge` or `StatusBadge` with visible text and status dot.
- Variants: neutral, good, warning, serious, critical, accent.
- Accessibility: color never carries status alone.

### Tabs
- Structure: shared Fluent-backed `Tabs` primitive.
- States: selected, hover, focus, disabled supplied by Fluent.
- Layout: horizontally scrollable when space is constrained.

### Operational calendar, daily reports and KPI
- Setelan Gudang uses shared tabs for Material & WO, Kalender kerja and KPI. The document owns scrolling; reviewed changes use ResourceForm and WarehouseCommandDialog. Calendar rows name dates, sources and edit actions; source errors remain visible beside saved holidays. Moving a holiday can cross years. KPI count targets and inactivity days are distinct labeled settings.
- Laporan Pagi / Sore shows the selected date, optional holiday status, full-scope completeness totals and both slots for each technician. Today submissions retain notes and optional photo bytes through review/retry. Revisions are immutable and photos load privately only in an opened detail. History has its own month filter and pagination.
- KPI uses one shared panel in the sidebar page and Dashboard tab. Month, team and technician filters apply to chart, table and average together. Horizontal score bars use the existing accent and surface tokens, with visible names and percentages; detail tables expose actual, target and percentage for each metric. Bars reflow in the document at 375px and have text equivalents. No motion or dependency is added.
- Empty, invalid and future KPI months clear results and show a field error. B2B history has an independent month control and permits an empty future month. Existing Fluent focus, token spacing and wrapping tables apply; no new accessibility debt.

### Provisioning workspace
- Structure: page header, safety summary, shared `Tabs`, and one section panel at a time.
- States: loading, empty, error, read-only, provisional, rejected, running, rollback, manual reconciliation.
- Accessibility: semantic headings, labeled controls, keyboard-operable tabs and buttons, `aria-live` for execution state, and stable rejection codes shown as text.
- Layout: detail grids collapse to one column on narrow containers; compact summary metrics may remain a readable 2×2 grid. The document remains the only vertical scroll owner.

### OLT device ONU snapshot

- Shared OLT detail exposes `ONU di OLT` only with both OLT-view and provisioning-view permissions, independently of customer, map, and ODP access. The customer-linked list is `ONU Pelanggan`; `ONU Baru` retains its provisioning behavior.
- Structure: read-only device-source summary, application read timestamp, 15-minute cache note, manual Refresh button, visibly labeled serial/name/ONT ID search, and the shared `DataTable`. No customer association or provisioning action is added.
- Tab activation reuses a successful per-OLT snapshot for 15 minutes after receipt. The browser-memory cache survives tab/detail remounts, clears on session/profile changes or a full page reload, and does not poll in the background. Expired snapshots are reread on the next activation; Refresh bypasses the cache. Loading disables Refresh. Requests remain shared across tab changes; late results never replace another OLT's view or repopulate a cleared session cache. Errors have their own alert and evict any previous snapshot; there is no automatic retry.
- Device values are never inferred. Columns with no values in the full snapshot are hidden, independently of search filtering. Partially populated columns remain visible with `—` for missing cells; partial/unsupported-field warnings do not produce a callout. Up/down times are verbatim OLT-clock strings, distinct from the application's ISO read timestamp. An unmatched search says only `Tidak ditemukan pada hasil baca ini`.
- Geometry: the available-column grid scrolls inside its own table region on wide containers without empty tracks for hidden fields. At container widths up to 48rem, actual Fluent grid rows become full-width detail cards with explicit labels for available columns. Long serials, names, and times wrap. Container sizing also covers compact inventory/map blades, independent of viewport size; the primary page never needs horizontal scrolling at 375px or 768px.
- Accessibility: existing Fluent buttons, fields, tabs, and text-plus-dot badges; labeled section and search; live loading/result count and error alert. Card labels remain real text, and the same grid cells preserve keyboard navigation. Existing tokens, spacing, typography, and scroll ownership apply without new dependencies.

## 6. Motion & Interaction

Only existing Fluent hover/focus transitions and the current 50-150ms card/button feedback are used. Motion communicates interaction state only, animates transform/opacity, and respects the global reduced-motion rules.

## 7. Depth & Surface

Use the existing mixed Fluent strategy: thin semantic borders plus `--shadow-sm` on content cards, no decorative glow, glass, or one-off elevation. Data grids remain flat through `.table-card`.

## 8. Accessibility Constraints & Accepted Debt

- Target WCAG 2.2 AA, visible focus, complete keyboard reachability, semantic labels, non-color status communication, and responsive reflow at 375px.
- Production apply remains disabled whenever preview validation, capability certification, or management protection is incomplete.
- Accepted debt: none.

## Map basemap controls

- All `LocationPicker` forms, including inventory, customers and `/express-psb`,
  share the same Google basemap presets and Fluent selection control as `/map`.
  The picker places the switcher above its canvas, below address search, with
  responsive wrapping and the existing spacing tokens. It loads only raster
  basemaps and its location pin, without network tile requests or API key setup.
  Switching preserves the camera, pin and draft coordinates; the shared
  `ftth.map.basemap` preference applies when either surface opens. Tile errors
  keep the alternative view selectable, and manual coordinates/search remain
  usable when WebGL is unavailable.
- `/map` keeps MapLibre and uses the existing Fluent `Segmented` control at the top
  left beside “Lokasi saya”: Google Maps (roads, the default view) and
  Google Earth (Google satellite imagery with road labels). The last option's
  tooltip explains that it is a satellite view rather than Google Earth 3D.
- Each raster provider has its own source, tile zoom limit, and attribution. Only
  the selected basemap is visible; switching preserves the camera and network
  overlays. Tiles use HTTPS, and external providers receive no application token.
- The selection persists in `ftth.map.basemap`. Legacy `streets`/`dark` and unknown
  values, including retired `default`, become Google Maps; legacy `satellite` becomes Google Earth. The saved mode
  is used for the initial style. Tile request errors appear beside the switcher
  with an instruction to choose another view.
- At mobile widths the switcher and location action form two rows, leaving room
  for the settings button. The legend leaves room for attribution and navigation
  controls. Controls retain Fluent focus and pressed states and
  hide during map editing. The settings drawer contains legend, heatmap, and
  network layer controls. Google modes use direct raster tile endpoints without
  an API key; availability follows the providers' responses. The road view hides
  points of interest and transit markers while keeping street labels visible.

## 9. Warehouse transaction controls

### Reference warehouse and field work

- Owner-only Perpindahan Gudang shows the current workflow, a reviewed stop on
  new legacy documents, server-reported blockers with links to their resolution,
  and a separately reviewed activation reason. Drain has no transaction replay:
  an unconfirmed response requires a workflow read before another attempt.
  Activation retains the exact server epoch/hash, session and retry key. A fresh
  review is required after conflict. Subscription locks preserve read access.
- Arsip Gudang uses dedicated read-only lists and document details for historical
  receipts, transfers and stock. Existing inventory permissions and location scopes
  apply independently of reference grants. Archive links stay inside the archive;
  names, exact quantities and paginated history use the existing table primitives.
  The archive describes current ledger positions as current, not frozen at activation.
  Both pages keep existing tokens, focus, scrolling and 375px reflow.
  Warehouse table cells grow with wrapped document names and quantities so the
  complete text and its link remain inside the row at every container width.

- Operator Work Order separates Belum ditugaskan source requests from assigned
  tasks using the shared Tabs. Source details show the customer, instructions,
  area and schedule. Tugaskan teknisi opens the shared resource form with only
  matching active work types and one active NE/FO technician. The review preserves
  source identity and uses the existing retry and conflict controls. Pending source
  details are operator-only; technicians see the task after dispatch.
- In reference mode PSB Express creates the customer and pending installation
  together. It explains that technician assignment is the next step and links the
  success document directly to that installation. The legacy technician picker is
  shown only after the server confirms the legacy workflow. Workflow failures are
  visible and creation stays unavailable until the workflow is known.
- Owner-only Jenis Pekerjaan lists named photo slots, material requirements and
  active state. The resource form reviews 1–12 unique photo names before saving.
  Editing or deactivating affects new assignments; existing WO retain their
  immutable type snapshot. Deletion reviews the revision and explains the
  used-type restriction. Uncertain deletion retries retain the session and key.
- Operator Work Order uses the same list and document as field tasks. Create and
  edit open a resource form with named type, scoped area, optional customer and
  a pure active NE/FO technician. Each directory is searched and paginated at 25
  rows; saved selections resolve through scoped detail reads. Local scheduling
  preserves the original instant when unchanged. The review names the assignee,
  instructions, priority and schedule. Closed tasks have no edit or reassign action.
- Reassignment reviews the previous and replacement technician and explains that
  previous photos cannot satisfy the new assignment. Revision conflicts offer a
  reload; uncertain responses keep the same command. Overdue filtering is explicit
  in the list. Map drafts hydrate only validated customer and instruction fields.
  Existing form scrolling, tokens, focus and mobile wrapping apply unchanged.
- Stock Opname starts with a named material and location, followed by an immutable
  book snapshot. Physical quantity starts blank and accepts zero. Review shows
  book, physical and exact difference before adjustment. Serial book positions
  and saved physical serials paginate at 25 rows. A stock conflict requires a fresh
  snapshot; a saved audit remains read-only with actor, time and reason.
- Retur Material links from Material Saya and shows named own stock, exact quantities,
  destination and reason before submission. Submission and rejection leave custody
  unchanged; Admin explicitly confirms physical receipt before stock moves. Saved
  details paginate serial positions at 25 rows and show submission and decision
  with the actor and timestamp in the timeline.
  Conflicts retain the pending document and offer a reload of current stock.
- Material requests use a named recipient, reason and exact material quantities.
  Technicians submit for themselves; Admin can select a technician or warehouse
  with searchable paginated choices. Proposed materials are available only for
  procurement. Current scoped warehouse and technician balances remain visible
  through submission and quantity review, including zero-stock warehouses.
- Request detail separates original, approved, received and fulfilled quantities
  from the actor timeline. Admin may reduce quantities; Manager decisions follow
  the policy captured at submission. Partial receipt and handover use reviewed
  resource forms. Uncertain responses retain the command and lock dismissal until
  retry resolves it. Material Saya links to requests; only the tenant owner sees
  approval and overdue settings. Lists, material lines and timelines paginate at
  25 rows and retain readable labels at phone widths.
- Reference receipts require a named destination and material lines. Supplier,
  delivery reference, lot/reel code and costs are optional. Review states that
  saving immediately adds available stock. Reference transfers select named
  source/destination and available warehouse positions; saving immediately moves
  the exact quantity. Saved receipt/transfer documents are read-only, show the
  actor and local timestamp, and paginate physical serial lines. Shared resource
  forms retain drafts and lock dismissal during pending or uncertain commands.
- These reference actions use the existing full-width tables, resource forms,
  mobile field labels and Fluent controls. Legacy receipt inspection and transfer
  acknowledgment retain their separate historical workspace.

- Tenant workflow selects the reference workspace from an authenticated server read.
  Technician work is a full-width document: task instructions, named required photos,
  own material quantities, completion review, then durable activity history. The
  document owns scrolling; there are no nested scrolling task panels.
- Task lists use plain states: Belum selesai, Ada kendala, Selesai, Dibatalkan.
  Technician and warehouse views share the existing Fluent fields, flat tables,
  searchable paginated pickers and exact quantity controls. All sections reflow
  at 375px and use existing color, spacing and focus tokens.
- A required photo has its slot name, receipt state and private download action.
  Upload reviews retain the original bytes, slot, revision and retry key. Material
  selection reads current own custody; completion reviews refreshed assignment,
  revision, photo receipts and stock before sending. Offline drafts remain visible
  and cannot post; reconnect refreshes the source before review.
- Empty material stock explains when handed-over stock appears. Completion history displays the named
  material, exact quantity, serial and submitted photos. No accessibility debt
  or new visual dependencies are introduced.

- Keep the existing flat tables, command bars, Fluent fields, `Modal`, `ConfirmDialog`,
  `Badge`, `EmptyState`, and page stacks. Warehouse operations follow the task-based Gudang menu order above; field custody belongs under `Material Saya`.
- Quantity fields show the unit in their label. Cable is entered in metres with at
  most three decimal places; decimal comma or point is accepted without thousands
  separators. Whole items reject fractions. Convert with integer arithmetic and
  send base-unit strings; never pass a material quantity through floating point.
  Readouts use Indonesian decimal commas and always keep the visible unit.
- Serial lookup accepts keyboard/scanner Enter and an explicit search button.
  Lookup only selects a candidate; it never submits a movement. Manual entry remains
  available. Show the SKU name, serial, location name, condition and owner together.
- Repeated line tables show names first, exact quantity/unit and a labelled state.
  Stable IDs are available as secondary audit references, never the only row label.
  On narrow screens retain the same controls and reading order with wrapping rows.
- A mutation confirmation describes the document, revision, quantity and destination.
  A pending request disables duplicate submission and dismissal. A failed request
  retains the original payload and idempotency key for retry; editing creates a new
  explicit command only after its previous outcome is resolved. No browser-generated
  approval hash or guessed stock identity is sent as authority.
- Shared states are loading, actionable empty setup, denied, scoped empty, pending,
  field validation, stale/conflict, retry and confirmed success. Unknown response
  shapes display an error, not an empty successful table. History shows local dates
  while preserving the server's UTC timestamp and exact document revision.
- Route permissions are independent: an approval-only user can reach approvals
  without stock-browsing permission. Cost controls require their separate permission.
  Unknown warehouse routes show an explicit unavailable page. Browser acceptance
  uses the isolated real API on desktop and a 375px mobile viewport, with no warehouse
  response mocks.
- Master forms use named, searchable selectors with explicit previous/next pages.
  A selector keeps its current selection visible when searching another page; an
  empty result means no matches in the current access scope. It must not silently
  treat the first page as the whole directory. Optional references have an explicit
  empty choice. Scope changes show the selected user, location, current access and
  revision before confirmation. Empty area grants explain the warehouse restriction
  and link to authorized area/user setup.
- Warehouse tables keep their column headers for assistive technology. At mobile
  widths each row pairs a visible field label with its value and keeps the action
  menu reachable. Repeated visual labels are hidden from the accessibility tree;
  cell names and table semantics continue to identify the original data.
- Receipt work uses a full-width draft form and a separate saved document detail.
  Show supplier, delivery reference, source, quarantine and exact material lines.
  Serial entry accepts newline batches and scanner Enter without submitting stock.
  Receive, inspection and bin placement are separate reviewed actions with explicit
  quantities and destinations. Accepted stock remains quarantined until placement.
  Private evidence has named type/date labels, pagination, download and a visible
  stale-intake state. Success reloads the durable document and revision.
- A saved blind count draft has an explicit edit action for its original requester.
  Hydrate all saved positions and named assignments through the bounded draft read;
  never load stock quantities or infer missing rows from the first picker page.
  Render at most25 assignment rows per editor page while retaining the complete plan.
  Invalid saved positions/counters require reselection. Confirm the saved revision
  before PUT and reload conflicts. Once a count starts, its scope and rounds are fixed.
- A saved transfer draft has an explicit edit action for its sender. Prefill the
  persisted route, receiver, reason and exact quantities; resolve saved positions
  through the scoped stock API. Missing historical positions require reselection.
  Show an inactive receiver and require an active replacement. Review uses the
  saved revision; conflicts reload the actual draft before another attempt. Editing
  does not dispatch or reserve stock, and posted transfers have no edit action.
- Stock exploration separates SKU totals, physical positions, serialized devices,
  and lot/reel lineage. Reservation and custody views filter durable data before
  pagination. Label totals that include consumed material; show available separately.
  Low-stock markers compare current scoped availability with the actual SKU minimum.
  Unknown provenance/unit/cost and restricted cost remain distinct states. Tree rows
  keep parent links, exact quantities and split/active labels without summing parents
  again. A failed conservation check is visible and never repaired by reading.
- Field material forms use the same controls and exact units at mobile widths. A
  scanner selects a serial from the authorized document or current own custody;
  Enter never acknowledges it. Camera scanning starts on an explicit user action,
  releases its stream on close, and leaves manual entry available on every error.
- Offline field edits are labelled drafts in the current tab. Previously loaded
  quantities are labelled as the last server snapshot, and mutations stay disabled.
  Reconnection requires fresh source, assignment and revision checks before review.
  Switching accounts or leaving the page clears these unsent local drafts.
