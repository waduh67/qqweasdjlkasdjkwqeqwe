# Azure resource workflow release

Warehouse filters/actions and app-wide creation/table presentation now follow the supplied Azure layout. Creation uses Basics → review → save; stock/location search is a single picker; table cells are plain and single-line.

Status: **deployed and verified** at https://ftth.karuhundeveloper.com/warehouse/catalog?tab=skus.

- Source commit: `af6580398d4c77aff4f928082c5a88a17b9711f6`.
- Branch: `work/azure-resource-workflows`.
- Web image: `sha256:16f050ef809af962b60fc5947da58695109c87f079ca6245b17a399da7121f6c`.
- Previous web image retained: `sha256:7923f38708d7d2e9f8252b033e586f4747c2d8ea6c49ff6e6bb5fbbc69915aa3`.
- Private release directory on `70.153.16.143`: `/opt/ftth/setup-private/ui-20260927-resources-af658039`.
- Activation verifies all 14 served static files against the tested build and the public index against the deployed index.
- All 14 existing non-web containers kept their IDs, images, start times and restart counts, including both Drive services. Backend health and FTTH/Drive HTTP checks passed.

## Verification

TypeScript/Vite build passed. All **630 tests in 126 files** passed. Firefox against the production build passed actual local SKU/customer saves, no-write review, Back retaining fields, validation, discard confirmation and mobile bounds. Independent visual/source reviewers closed with no unresolved P0–P2 issue.

Authenticated Firefox on production passed **15 captures** covering warehouse lists, customer/tenant/VPN lists and item creation/review/mobile. Review, Back and discard worked; no page errors, document overflow or business-write attempts occurred. Test writes were confined to local QA.

Release-helper tests: **9 passed**, including guarded rollback and distinct preservation of both Drive containers. Raw receipts and screenshots stay private under `.omo/runtime/ui-ux/azure-warehouse-20260927/`; the deploy host retains `release.json`, `activation.json`, `pins-before.json`, image archive and rollback helper.

## Recovery

Clone/fetch `work/azure-resource-workflows` and read `AZURE-RESOURCE-WORKFLOWS.md` plus project-root `design-qa.md`. Implementation checkpoints: `57ab2347`, `cb81ef65`, `50d6c171`, `af658039`.

The additional private recovery archive contains an incremental Git bundle from `5beed944`, review/build/test evidence and documentation. A recipient using that bundle needs the base commit (available in the remote repository and the previous recovery delivery).

Guarded rollback, if needed, runs on the deployment host:

```sh
sudo python3 /opt/ftth/setup-private/ui-20260927-resources-af658039/deploy-web.py rollback /opt/ftth/setup-private/ui-20260927-resources-af658039
```

The helper refuses unexpected image/pin changes; inspect its receipt before retrying an interrupted release. This UI revision does not alter backend code or schema. The old warehouse owner's acceptance remains a historical receipt, separate from this new request.
