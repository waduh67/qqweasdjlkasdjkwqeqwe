# Dark network map — 13 October 2026

## Behavior

**Peta gelap** is available beside Google Maps and Google Earth on the network map and all location pickers. It uses the existing Google road tiles with dark geometry, muted roads and readable labels. Public places and transit markers remain hidden, leaving the existing colored network assets and cables prominent. No new provider credentials or dependencies are required.

The shared `ftth.map.basemap` preference restores `dark` after refresh. Existing road/satellite choices and the default road view are preserved. Switching changes raster visibility in place, retaining the map canvas, camera, network layers and form draft. Google attribution remains visible.

The picker can wrap within narrow containers. The map settings button has enough CSS specificity to retain its themed background over the dark map instead of inheriting Fluent's transparent subtle-button background.

## Verification

- **24 existing map/location/toolbar tests passed**, updated for the new mode, saved preferences, network layer order, keyboard selection, provider failures and location editing.
- Lint and production build passed; pre-existing unrelated warnings remain.
- **4 browser checks passed** in Chromium and Firefox at 375px and 1440px. Real Google tiles rendered with synthetic network vector tiles, no application token was sent to the tile provider, switching retained the canvas, preferences survived reload and the toolbar fit its container. Final screenshots inspected on desktop and mobile.
- Firefox WebGL validation ran with a virtual display because the headless environment could not initialize the map. No application workaround was introduced.
- An initial CARTO candidate returned API-key-required artwork with HTTP 200 and was discarded after visual inspection. The final implementation uses the existing Google tile source only.

## Release and recovery

Branch: `feat/dark-network-map-20261013`, based on main `8b3d4275` (PR #63). Merge after final CI passes, then deploy using GitHub Actions and verify the running revisions plus the public map bundle. No production network/customer records are created for verification.

Local evidence and the temporary browser review harness are in `.omo/runtime/dark-map/` under `/home/fajar/ftth/ftth-azure-forms-20261012` (ignored). The merged PR and successful Actions deployment are the release authority.
