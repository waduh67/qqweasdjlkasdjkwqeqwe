## Current runbook verification at migration178.9

The operation/review/deploy guidance now consistently names178.9 and next178.10.
All relative documentation links resolve. Current CI36131581036 executed22 real
browser cases and6 V172 upgrade/cutover/restart cases against the same product inputs.
Its app-role read-only preflight passed for both upgraded tenants, and all6 deliberately
wrong migration/unit/cutover probes failed for the intended reason. The2legacy customers,
2ONUs and2UI-created catalog SKUs survive restart with no invented available stock.
Raw output hashes were revalidated privately. Safe source-bound proof:
 task47/current-runbook-verification.json, referencing the revalidated task46 CI proof.

The operational retention choice is explicitly confirmed by the user: reject deletion
with protected history and use Suspend; empty tenant deletion remains available.
The documented local guard commands, including historical overlay checks, now all
execute successfully (28tests plus actionlint). Full server regression and independent
final approval remain separate pending gates; no production readiness or deploy claim.

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
