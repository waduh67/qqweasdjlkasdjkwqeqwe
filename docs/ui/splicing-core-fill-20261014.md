# Filled splicing cores, 2026-10-14

Branch: `fix/splicing-core-fill-20261014`, based on main `ba267010` (PR #69).

Core buttons in the interactive splicing board now use the full physical fiber color as their background. Their centered numbers use whichever of black or white has the higher WCAG contrast ratio. The production palette has a minimum number/background contrast of 4.83:1. Missing or invalid colors fall back to the normal theme colors.

Hover, selection and disabled states preserve the fill and number contrast. Selected cores retain a separate outline and check mark, positioned away from the number. Splitter/port buttons and the connection flow are unchanged.

Validation:
- Production build, lint (existing unrelated warnings only) and whitespace checks passed.
- All 18 existing splicing unit tests and 14 Chromium/Firefox scenarios passed.
- The existing desktop connection scenario also passed in both browsers with a dark color scheme (2 runs). Inspected light/dark and narrow-panel screenshots.
- Reviewed contrast against all 12 production fiber colors. No additional regression tests added for this visual change.

Private evidence: `.omo/runtime/splicing-core-fill/`. Final CI, merge, Actions deployment and live activation will be recorded in the PR. Keep changes pushed; deploy through GitHub Actions.
