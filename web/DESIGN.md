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

- `/map` keeps MapLibre and uses the existing Fluent `Segmented` control at the top
  left beside “Lokasi saya”: Default (OpenStreetMap), Google Maps (roads), and
  Google Earth (Google satellite imagery with road labels). The last option's
  tooltip explains that it is a satellite view rather than Google Earth 3D.
- Each raster provider has its own source, tile zoom limit, and attribution. Only
  the selected basemap is visible; switching preserves the camera and network
  overlays. Tiles use HTTPS, and external providers receive no application token.
- The selection persists in `ftth.map.basemap`. Legacy `streets`/`dark` and unknown
  values become Default; legacy `satellite` becomes Google Earth. The saved mode
  is used for the initial style. Tile request errors appear beside the switcher
  with an instruction to choose another view.
- At mobile widths the switcher and location action form two rows, leaving room
  for the settings button. The legend leaves room for attribution and navigation
  controls. Controls retain Fluent focus and pressed states and
  hide during map editing. The settings drawer contains legend, heatmap, and
  network layer controls. Google modes use direct raster tile endpoints without
  an API key; availability follows the providers' responses.

## 9. Warehouse transaction controls

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
