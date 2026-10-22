# FTTH NetOps Console Design System


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

Body and numeric text use local Hanken Grotesk with system fallbacks; headings use local Space Grotesk. Body copy is 14px/20px, page headings 28px/36px at 600, section headings 20px/28px at 600 and card headings 16px/22px at 600. Existing metadata may remain smaller.

## 4. Spacing & Layout

Spacing follows a 4px rhythm, expressed in the UI as compact 0.25rem/0.5rem relationships, 0.6rem row gaps, 1rem section gaps, and 1.15rem × 1.25rem card padding. `.stack` owns vertical flow, `.row` groups inline controls, `.spread` places page headings with actions, and `.card` groups a coherent task. Desktop content lives inside the app shell; below 820px the sidebar becomes a drawer and content padding drops to `1rem 0.85rem 2rem`. At 720px, page actions and form controls reflow to readable full-width controls.

## 5. Components

### Page header

- **Structure**: `.spread` with title and contextual actions.
- **States**: actions expose enabled, disabled, and loading labels.
- **Accessibility**: heading remains semantic; disabled actions retain their explanatory nearby context.

### Card

- **Structure**: `.card.stack` with a section heading, grouped content, and optional footer actions.
- **Spacing**: standard card padding and a 1rem stack gap.
- **States**: rest uses `--surface`, `--border`, and `--shadow-sm`; clickable cards add hover, active, and visible focus states.
- **Layout**: card content never owns viewport scrolling.

### Form field and select

- **Structure**: Fluent `Field` wraps `Input`, `Select`, or `Textarea`.
- **States**: default, focus, validation error, disabled, and write-only-secret hint.
- **Accessibility**: every control has a visible label; help and error copy stays adjacent to the control.

### Buttons, badges, and feedback

- **Variants**: primary, subtle, destructive, status badge, inline warning callout, and toast.
- **States**: hover, active, focus-visible, disabled, loading, success, warning, and error.
- **Motion**: existing control feedback uses 120–180ms easing and respects reduced-motion styles already in the application.

### Data table

- **Structure**: flush `.card.table-card` with labelled rows.
- **Responsive behavior**: below 720px rows become labelled cards instead of requiring primary content to scroll horizontally.

## 6. Motion & Interaction

Interactive feedback is restrained: opacity and transform only, typically 120–180ms ease. Clickable cards use a 1px press shift; navigation drawers use an 180ms transform. Visible focus rings use `--accent`; non-essential movement should not be required to understand a state.

## 7. Depth & Surface

Cards use neutral borders and 24px radii. Table frames share that radius with flush rows and no shadow. Overlays retain their existing elevation and resource scroll ownership.

## 8. Accessibility Constraints & Accepted Debt

### Constraints

- Target WCAG 2.2 AA: body text meets a 4.5:1 contrast floor; large text and non-text indicators meet 3:1.
- Every interactive element must have a visible keyboard focus state and remain operable by keyboard.
- Labels, helper copy, validation, and status messages remain textual; color alone does not communicate meaning.
- Responsive screens must remain one readable column at 375px without horizontal scrolling of primary content.
- Respect `prefers-reduced-motion`; no state depends solely on animation.

### Accepted Debt

| Item | Location | Why accepted | Owner / Exit |
|---|---|---|---|
| Screen-specific inline layout styles | Existing page and organism components | Existing Fluent composition mixes shared CSS primitives with one-off inline layout values. | Consolidate only when a repeated pattern emerges. |
| Some legacy text and component spacing vary slightly from the 4px rhythm | Existing screens | Specialized layouts retain their existing geometry while shared controls follow the internal contract. | Address during a dedicated visual-system consolidation. |
