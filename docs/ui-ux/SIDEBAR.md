# Azure left navigation — 27 September 2026

User requests matching the supplied Azure Portal left navigation, replacing the remaining legacy sidebar design. Reference is the user's1910×921 Compute infrastructure screenshot; fresh Firefox production capture and computed measurements are under `.omo/runtime/ui-ux/sidebar-20260927/`.

Observed mismatch: inset rounded active pills,40px rows with extra gaps, bold12px section headings with right chevrons,36px search input, and a brand/context block above the menu. Reference uses full-width32px rows, square gray selection with a2px blue left edge,13px regular section labels with leading chevrons,16px icons, nested child indentation and a24px search control beside collapse. The sidebar remains264px wide, matching the reference's roughly265px menu boundary.

Implementation plan: consolidate navigation styling, use official Fluent16px icons, move app identity/context controls into the global header, and place compact search/collapse controls at the top of navigation. Preserve permission filtering, active route discovery, saved groups, search, desktop collapse and mobile navigation. Validate in Firefox with the supplied reference, actual navigation interactions, narrow/desktop layouts and keyboard operation; use the existing guarded web-only release workflow after checks pass.

Status: released and verified; current release details below.

Implementation now uses one `navigation.css` instead of conflicting rules across two stylesheets. Ten focused navigation/shell tests and TypeScript/Vite build pass. Private read-only Firefox preview verifies context switching, visible mobile header controls, and focus returning to the header when closing the drawer. Independent review findings for keyboard focus, coarse-pointer target sizes and reduced-motion precedence are fixed. Context popup now uses Fluent's native radio indicator and layout, removing obsolete manual positioning/duplicate checkmarks. Frozen Firefox navigation +44-route QA and release remain pending.

Firefox navigation and44-route audits both pass2/2 at01535e83; no failed/skipped tests. Final visual calibration changes only the search border: sampled reference edge is#8a8886 (formerly#605e5c), with a2px input corner. Active row background already matches the reference pixel-for-pixel at#edebe9. Final build and browser visual check follow this cosmetic delta; functional audit behavior is unchanged.

## Released and verified

Source `97674d0e69618f70990a72fd9d35e108fce158ca` is live as image `sha256:69a96121fade5e06873742134f2220a8d20b1dd94fec7e9e74b67772af7199bc`. Release/rollback directory: `/opt/ftth/setup-private/ui-20260927-sidebar-97674d0e/`; [operations](../../deploy/AZURE-FTTH-OPERATIONS.md#rilis-navigasi-azure--27-september-2026) includes the guarded rollback command.

Final TypeScript/Vite build and calibrated Firefox preview pass. Runtime Nginx HTTP check verifies `/customers` and all14 static files against final dist hashes;6 rollback checks pass. Activation replaced only web, preserving the other12 FTTH containers and Drive proxy. Post-deployment authenticated Firefox audit passes14 screens with four Segoe font faces loaded, no page errors or document overflow; dedicated sidebar capture also verifies Platform↔Tenant navigation, mobile header control bounds and focus return. No production business forms were submitted.

`calibrated-sidebar.png` and `live-sidebar.png` have identical SHA256 `4ec07b2727aa605aba7478372afd23026d07d250d2a076b763ae344e89e9f567`. The reference/before/after comparison is `comparison.png` in the private evidence folder. This demonstrates that the reviewed sidebar is the one served in production, within the same Firefox/viewport configuration. Source icon semantics remain appropriate to FTTH; menu labels and destinations are preserved. Owned QA services stopped; test volumes retained.
