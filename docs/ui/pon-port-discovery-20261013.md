# PON discovery and inventory import

## Operator flow

Open an OLT, select **PON Port**, then **Deteksi port**. The panel reads SNMP, lists physical PON labels and detected ONU counts, and selects new ports. Existing inventory ports cannot be selected. Review the selection with **Tinjau + buat**, then **Buat**. Closing detection does not change inventory or reload the document.

**Tambah manual** remains available. HSGQ labels such as `PON1` are accepted; lower case and leading zeros normalize to the same identifier (`pon01` → `PON1`). Numeric labels such as `0/1` and `0/1/1` remain valid. Never translate `PON1` into an invented frame/slot label.

## Implementation

- Discovery is initially supported for HSGQ. Its existing SNMP adapter already maps ONU indexes to `PONn`; this change exposes the same mapping for inventory discovery. Other vendors retain manual entry until their mapping is defined.
- `GET /api/monitoring/olts/{id}/pon-ports` requires both `network.olt.view` and `monitoring.provisioning.view`. It looks up the tenant-scoped OLT before any device request and performs no inventory writes. SNMP I/O runs outside a database transaction.
- Discovery reads IF-MIB `ifName` and `ifDescr` for physical ports, then the adapter's model-specific serial table for populated ports and counts. Only recognizable physical names are accepted; ONU subinterfaces and uplinks are excluded.
- Empty ports can be found when the device exposes recognizable physical interface names. If it does not, the panel warns that ports without ONUs may be missing. A failed ONU read produces unavailable counts, not a false zero. Total read failure returns HTTP 502 rather than an empty successful result.
- `POST /api/olts/{id}/pon-ports/import` requires `network.olt.update`. The entire batch is normalized and validated before insertion. `ON CONFLICT DO NOTHING` preserves existing IDs, status, descriptions and connections, including concurrent retries. New rows and their audit record share one transaction. No schema migration is required.
- The PON tab now uses the shared command bar, search, resource table and nested resource forms. Checkbox styling is preserved within plain-text table cells. Detection can close without an unsaved-form prompt; manual forms retain their draft protection.

## Validation and recovery

Feature branch: `feat/pon-port-discovery-20261013`, based on main `7fd75c15`.

Local validation commands:

```sh
scripts/warehouse/qa.sh server \
  --tests '*PonPortDiscoveryServiceTest' --tests '*PonPortDiscoveryControllerTest' \
  --tests '*PonPortImportIT' --tests '*OltOnuInventoryServiceTest' \
  --tests '*OltOnuInventoryControllerTest'
cd web
npm run lint
npm test -- --maxWorkers=2
npm run build
npx playwright test --config=playwright.network.config.ts pon-port-discovery.spec.ts
```

The 40 targeted server cases passed, including real PostgreSQL concurrent imports, rollback of invalid batches, manual-label compatibility and cross-tenant HTTP access. Tests also cover SNMP partial failures, unsupported devices and read/write permissions. UI tests exercise explicit review, retry, duplicate-submit prevention and closing during a pending read. Browser coverage includes Chromium and Firefox at 375px and 1280px, plus read-only access and failed detection.

The general `ModularityTests` check reports dependencies in IAM/tenancy, fulfillment/workorder and warehouse modules outside this change. A separate checkout of base commit `7fd75c15` reproduced the same 383 violation entries (ordering excluded), with no new violations. Keep this visible; do not suppress that check or report the entire server suite as green.

Private local evidence: `.omo/runtime/pon-discovery/`. Browser screenshots and traces: `.omo/runtime/network-ui/browser-artifacts/`. These directories are ignored. GitHub CI, merge and deployment run links belong in the PR. Deploy through the existing GitHub Actions workflow, then verify readiness, activation receipt and running server/web OCI revisions against the merge commit. No production PON ports are created automatically by deployment.
