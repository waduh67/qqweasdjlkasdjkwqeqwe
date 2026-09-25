# Task47 operational runbook — verification pending

Saved docs/warehouse.md, warehouse-review.md, migration/work-order/mobile links,
deploy gates and the read-only scripts/warehouse/preflight.sql. Documented flows
have current real browser proof: 22 cases plus six historical upgrade/restart cases.
All relative links and named UI labels checked against source. Full server R3 is
still running; no task47 completion is claimed.

The queued .omo/runtime/runbook-preflight-r2.sh holds the host lock after R3, uses
warehouse_app on an actual ENFORCED tenant with verified CABLE/MM stock, and checks
positive version178.7/MM/ENFORCED plus wrong version170, unitEA and cutoverLEGACY.
It must verify the exact denial reason for each negative. Do not edit the executing
or queued wrapper or preflight source. Archive results, fix reproduced issues and
rerun using a new wrapper name if necessary. Retain all QA volumes.
