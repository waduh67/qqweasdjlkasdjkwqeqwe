# NetOps Console Design System

## 1. Atmosphere & Identity

NetOps Console is a calm, dense operator workspace modeled on Fluent/Azure administration surfaces. Its signature is a flat command hierarchy: full-width data regions, restrained cards, one Azure-blue action accent, and explicit text-plus-dot operational states.

## 2. Color

All colors come from `src/index.css`: `--plane`, `--surface`, `--surface-2`, `--surface-hover`, `--border`, `--border-strong`, `--text`, `--text-2`, `--muted`, `--accent`, `--accent-hover`, `--accent-ink`, `--accent-soft`, `--focus-ring`, `--good`, `--warning`, `--serious`, `--critical`, and `--danger`. Light and dark values are defined together. Status color must always be paired with a text label or icon.

## 3. Typography

- Primary: the existing Fluent/Plus Jakarta Sans application stack.
- Page title: `.page-title`, 28px/36px, weight 600, tight tracking.
- Section title: Fluent `Text` size 400, semibold.
- Body: Fluent size 300.
- Secondary and metadata: Fluent size 200 with `.muted` where appropriate.
- Device IDs and hashes use normal text with `overflow-wrap: anywhere`; no competing monospace family is introduced.

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
  `Badge`, `EmptyState`, and page stacks. Put warehouse operations in a separate
  `Gudang & Logistik` navigation group; field custody belongs under `Material Saya`.
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
