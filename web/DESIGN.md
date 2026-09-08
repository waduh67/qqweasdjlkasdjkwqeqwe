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
