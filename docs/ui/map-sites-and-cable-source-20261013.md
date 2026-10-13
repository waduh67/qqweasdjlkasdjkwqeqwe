# Map site selection and cable source forms

## Behavior

The map has a separate **Site / POP** card beside the existing basemap/location card. Search by site name or code and select a site to center the map at zoom 16. On narrow screens the cards wrap without covering the map settings button.

The selected site ID is stored in `ftth.map.site:<tenantId>:<userId>`. Returning to Maps or refreshing restores that site using its current authorized coordinates. The list loads every API page, so sites beyond the first page remain available. A deleted or inaccessible site is removed from the saved selection after a successful read; temporary failures retain the preference and show retry. A late geolocation response cannot override a chosen site, and explicit **Lihat di peta** navigation takes priority over the saved site. **Lokasi saya** clears the site preference after a successful location lookup.

Cable creation's **Sambungan** step now shows source and destination as aligned summary rows. OLT sources use one required **Port PON** select, with occupied ports disabled and identified by their existing cable. The selected port is included in the final review. Read failures are shown separately from an empty port inventory and can be retried.

ODC, ODP and joint-box sources keep the existing per-core connection model. The UI no longer labels them as an empty source-port picker or displays a long technical explanation. It directs the operator to **Sambungan serat** after saving the physical cable. For example:

1. Save the ODC → joint-box cable with its actual core count.
2. In ODC **Sambungan serat**, connect the required splitter output to a core of that cable.
3. Save the joint-box → ODP cable.
4. In the joint box, connect the incoming cable core to the outgoing cable core.
5. In the ODP, connect the arriving core to the splitter input.

Drawing cables alone does not record all of these per-core connections. This change preserves the existing backend and save payload; it does not invent one source port for an entire multicore cable.

## Validation and recovery

Branch: `feat/map-site-picker-20261013`, based on main `ab15899b`.

Targeted tests cover cache restoration, current coordinates, pagination, account isolation, permission gating, stale responses, retry, storage failure, source-port occupancy and review/save payloads. Browser scenarios use the real map and vector-tile fixtures in Chromium and Firefox, including mobile/desktop layout, SPA navigation, refresh, delayed geolocation, explicit asset focus, and drawing OLT → ODC, ODC → joint box, joint box → ODP cables.

```sh
cd web
npm run lint
npm test -- --maxWorkers=2
npm run build
xvfb-run -a npx playwright test --config=playwright.map.config.ts
```

Private local evidence is under `.omo/runtime/map-sites/` (ignored). Commit and push before release. Use the existing GitHub Actions deployment, then verify public readiness, release activation and running server/web revisions. Add CI/deployment links and final counts to the PR. No schema migration or production data edits are needed.
