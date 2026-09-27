# Azure typography reference audit — 27 September 2026

User requests closer typography matching Azure Portal and offers browser DevTools access. Reference supplied: https://learn.microsoft.com/en-us/azure/azure-compute-fleet/overview. Microsoft Learn is a documentation surface; its heading/body sizes are not automatically the correct sizes for Azure resource lists.

Fresh Chrome/Linux captures and CDP `CSS.getPlatformFontsForNode` results are private under `.omo/runtime/ui-ux/typography-20260927/`. Same viewport 1440×1000, same browser, loaded fonts awaited. Current public FTTH `/customers` was authenticated read-only; no business form submitted.

| Sample | Computed styling | Actual rendered font in this Linux browser |
|---|---|---|
| Learn article title | 40px / 52px, weight600, normal letter spacing | Segoe UI Variable, downloaded webfont |
| Learn article text | 16px / 28px, weight400 | Segoe UI Variable, downloaded webfont |
| FTTH customer title | 28px / 36px, weight650, -0.5px spacing | FreeSansBold, local fallback |
| FTTH description | 14px / 22px, weight400 | FreeSans, local fallback |
| FTTH command | 14px / 20px, weight400 | FreeSans, local fallback |

FTTH declares Segoe UI in its font stack but currently defines no text `@font-face`; font requests were absent. Learn loads `https://learn.microsoft.com/static/third-party/SegoeUIVariable/SegoeUI-VF.ttf`. Its stylesheet uses the Segoe UI Variable Text alias as a fallback after locally installed Segoe UI. Thus identical font-family names in CSS do not establish identical actual fonts. This browser finding does not establish the user's Windows/macOS font choice.

A temporary browser-only probe applied that reference webfont to the unchanged FTTH page. CDP confirmed Segoe UI Variable was rendered; all original sizes/weights remained, including title650. Paired native-size crops are in `font-comparison.png`. No production stylesheet, application file, deployed font asset or release was changed. This is diagnostic evidence, not a finalized font distribution decision.

The existing title650/negative spacing is a separate candidate for correction once the Portal reference is measured. Antialiasing flags already match Learn; a generic smoothing CSS toggle alone does not solve the demonstrated font fallback. Pixel-level comparison also needs the same OS/browser/zoom/display scale.

Needed from the user's authenticated Azure Portal: one All resources/resource-list screenshot at100% browser zoom; Computed and Rendered Fonts for the page title and one resource-name text; OS, browser and display scale. Useful computed properties: font-family, font-size, font-weight, line-height, letter-spacing, font-variation-settings, font-optical-sizing and color. Public portal.azure.com returned403 from this environment, so no authenticated Portal font measurements are claimed. Keep the released Azure UI intact until the target is measured; then centralize font loading and typography tokens, compare in-browser, and use the existing web-only release/rollback workflow.
