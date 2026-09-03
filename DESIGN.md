# FTTH NetOps Console Design System

## 1. Atmosphere & Identity

FTTH NetOps Console is a compact operational console: clear, accountable, and calm under pressure. Its signature is Fluent/Azure-like administrative density, with white or charcoal surfaces, restrained blue action emphasis, thin separators, and modest elevation rather than decorative treatment.

## 2. Color

### Palette

| Role | Token | Light | Dark | Usage |
|---|---|---|---|---|
| Canvas | `--plane` | `#F8F9FA` | `#1B1A19` | Page background |
| Surface | `--surface` | `#FFFFFF` | `#201F1E` | Cards and controls |
| Raised surface | `--surface-2` | `#F3F4F6` | `#292827` | Secondary panels |
| Text | `--text` | `#1B1A19` | `#FFFFFF` | Primary copy |
| Secondary text | `--text-2` | `#484644` | `#D2D0CE` | Supporting copy |
| Muted text | `--muted` | `#605E5C` | `#A19F9D` | Hints and metadata |
| Border | `--border` | `rgba(0,0,0,.06)` | `rgba(255,255,255,.09)` | Card and field separation |
| Accent | `--accent` | `#0078D4` | `#2899F5` | Primary actions, links, focus |
| Accent hover | `--accent-hover` | `#106EBE` | `#62ABF5` | Interactive hover state |
| Success | `--good` | `#10B981` | `#34D399` | Successful and healthy states |
| Warning | `--warning` | `#F59E0B` | `#FBBF24` | Cautions and pending states |
| Serious | `--serious` | `#F97316` | `#FB923C` | Elevated caution |
| Critical | `--critical` | `#EF4444` | `#F87171` | Errors and destructive states |

Use the accent only for interaction and focus, never as decoration. Status always combines color with text or an icon.

## 3. Typography

The primary stack is `Segoe UI, system-ui, -apple-system, BlinkMacSystemFont, Roboto, Helvetica Neue, sans-serif`; numeric data uses the Fluent numeric stack. The base is 14px with a 20px line height. Page headings are 28px/36px at 600, section headings 20px/28px at 600, card headings 16px/22px at 600, body copy 14px/20px at 400, and captions use Fluent `caption1`. Text below 14px is reserved for existing metadata only.

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

The system uses a mixed border-and-subtle-shadow strategy. Standard cards use a 1px `--border`, `--radius` (4px), and `--shadow-sm`; higher-priority overlay surfaces may use `--shadow-md` or `--shadow-lg`. Tables intentionally stay flat with square corners and no shadow to preserve a dense administrative hierarchy.

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
| Some legacy text and component spacing vary slightly from the 4px rhythm | Existing screens | Preserving established operator screens avoids a visual redesign outside this task. | Address during a dedicated visual-system consolidation. |
