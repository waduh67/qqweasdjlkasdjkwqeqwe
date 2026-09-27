# Azure left navigation — 27 September 2026

User requests matching the supplied Azure Portal left navigation, replacing the remaining legacy sidebar design. Reference is the user's1910×921 Compute infrastructure screenshot; fresh Firefox production capture and computed measurements are under `.omo/runtime/ui-ux/sidebar-20260927/`.

Observed mismatch: inset rounded active pills,40px rows with extra gaps, bold12px section headings with right chevrons,36px search input, and a brand/context block above the menu. Reference uses full-width32px rows, square gray selection with a2px blue left edge,13px regular section labels with leading chevrons,16px icons, nested child indentation and a24px search control beside collapse. The sidebar remains264px wide, matching the reference's roughly265px menu boundary.

Implementation plan: consolidate navigation styling, use official Fluent16px icons, move app identity/context controls into the global header, and place compact search/collapse controls at the top of navigation. Preserve permission filtering, active route discovery, saved groups, search, desktop collapse and mobile navigation. Validate in Firefox with the supplied reference, actual navigation interactions, narrow/desktop layouts and keyboard operation; use the existing guarded web-only release workflow after checks pass.

Status: implementation in progress; production remains typography release3ce5993d / image15473555.

Implementation now uses one `navigation.css` instead of conflicting rules across two stylesheets. Ten focused navigation/shell tests and TypeScript/Vite build pass. Private read-only Firefox preview verifies context switching, visible mobile header controls, and focus returning to the header when closing the drawer. Independent review findings for keyboard focus, coarse-pointer target sizes and reduced-motion precedence are fixed. Context popup now uses Fluent's native radio indicator and layout, removing obsolete manual positioning/duplicate checkmarks. Frozen Firefox navigation +44-route QA and release remain pending.
