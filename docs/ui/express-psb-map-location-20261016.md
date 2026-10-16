# PSB coordinates, map visibility and installation status — 2026-10-16

## Behavior

- PSB may omit coordinates. The API stores `location: null` / `UNLOCATED`, never a synthetic `(0, 0)` point. The review shows “Belum ditentukan”. Partial and invalid coordinates remain errors.
- Unlocated PSB customers appear in the existing map placement picker. `Customer.relocate` now updates `locationStatus` with the point; previously placement failed a database invariant because it retained `UNLOCATED`.
- Regular customer create/edit accepts the same optional location. Detail and edit blades handle nullable API locations safely.
- The list/detail show one label, “Menunggu instalasi”, for a prospect with a pending subscription. Prospects without a subscription retain “Prospek”. Missing ONU inventory alone no longer marks active customers as awaiting installation. The PROSPECT filter is labelled “Belum aktif”. No subscription, billing, RADIUS or work-order activation behavior changes.
- Unmonitored customers use bright blue (`#3399ff`) instead of grey, with a 5 px marker and 1.5 px dark outline. Legend and affected-customer dots match. Online/fault colors remain green/red.
- Each map visit requests fresh vector tiles, while preserving site selection and the existing refresh after placement.

## Diagnosis

The reported live customer already had valid coordinates and a pending subscription. The user confirmed the grey point was difficult to see. Read-only inspection also found the point about 3.3 km from POP-CRB; no production customer data was moved or modified.

## Verification and recovery

- `scripts/warehouse/qa.sh server --tests com.duluin.ftth.OnboardingExpressPsbIT`: 8 tests pass on isolated PostgreSQL with real migrations/RLS. Covers omitted/null/valid/invalid coordinates, later placement and vector tiles without an ONU, tenant isolation, lifecycle labels, transaction rollback and pending RADIUS access.
- Targeted Vitest: 18 tests pass.
- Browser coverage: PSB with/without coordinates through all five steps, review, failed-save retry, and preserved draft at 375/1440 px in Chromium/Firefox; unlocated customer detail/edit; clickable blue markers on real dark/satellite basemaps and fresh tile requests on reopening.
- Frontend build and lint completed (existing lint warnings remain).
- Private local evidence: `.omo/runtime/express-psb-location/`. Browser screenshots use fixture customers only. Production deployment uses GitHub Actions after merging the PR.

No database migration is required. Rollback uses the previous verified server/web image pair. Nullable locations already existed in the schema; older frontends cannot safely open unlocated customer detail/edit, so retain this nullable handling in any rollback that preserves new unlocated PSB data.
