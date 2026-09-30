## Next serial-only review design (NOT IMPLEMENTED)

Current phase after c92151f5 is usage/deployment ordering (V178.3 applied). Finish
its server/web/KMP verification and checkpoint first. V178.4 is next free.

A serial-only WO has a real installed device but no bulk usage snapshot, so QA
cannot currently finish. Do not fabricate NONE, cable, another DEPLOY or a material
fact for the serialized asset. Preserve the existing prohibition on SERIAL bulk use.
Prefer an explicit immutable report-use review of already-posted deployments:

- An optional request list of deployment authorization IDs binds the user's chosen
  installation sources. Default null, @get:JsonInclude(NON_NULL), to preserve exact
  canonical bytes/hash for every older ordinary request and same-key replay.
- Required-material empty bulk lines remain invalid unless the plan contains only
  serialized lines, positive real requested deployment sources cover every line,
  and each actual installation belongs to this WO/plan/current assigned technician.
  Mixed plans cannot submit an empty review to hide unreported cable use.
- The service verifies current authority and warehouse/location access to ORIGINAL
  accepted receipts, not just current installed customer locations. Active assignment
  locks and actual receipt/deployment validators protect a new review. Revoked actors
  must not gain a new reporting path; inherited/reassigned review needs explicit policy.
- MaterialUsageSnapshot may append default-empty deployment witnesses. Old stored
  bodies/hashes must be returned byte-for-byte. A reviewed USAGE document advances
  the shared revision after its deployment; no movement, fact or posting is created.
- Generalize only the no-movement persistence branch: NONE still requires its reason
  and zero sources; DEPLOYMENT_REVIEW requires proved real sources. Record the normal
  operation/header/snapshot/identity atomically. Do not repurpose PostingFacts to fake
  a consume posting.
- A new normalized inventory_material_usage_deployment table can bind tenant/usage/
  authorization (RLS FORCE, immutable, created within usage transaction). Its DB
  validator derives the exact immutable witness from real authorization/execution/
  result/receipt/plan/assignment, validates owner results and frozen JSON equality.
  Only new reviews require the assignment still active. Historical usage reads after
  legitimate removal/reuse must remain valid, using the old immutable source refs;
  do not recompute history from the current-active list. Current deployment result
  validator already recognizes legitimate ended episodes.
- A scoped workbench context/endpoint must expose named actual installed choices,
  serials and authorization IDs for review. Web and KMP need an explicit confirmation
  path without quantity inputs. Current context only knows latestUsageId/useRevision;
  KMP currently does not decode plan lines. Add optional/default-compatible metadata,
  and include relevant source IDs in offline preflight guards. Avoid a client-only
  guess that treats every nonzero revision as a deployment review.
- Usage history must name reviewed installations rather than displaying an unexplained
  required-material record with zero lines. Serial review must not disappear from
  location-scoped history merely because it has no bulk usage_line.

Tests: real serial-only plan/issue/ACK/install/handover/review/complete/QA; no extra
movement or asset and1,000,000MM/10EA conserved (9 available,1 installed). Reject
missing/foreign/stale/revoked source IDs and mixed-plan empty review. Same-key replay,
old ordinary-request replay/hash, no-source NONE, and historical read after legitimate
removal/reuse must retain behavior. Existing WarehouseNumericLifecycleFixture can
accept an optional cable-plan flag while still receiving the baseline1,000,000MM.
Main numeric mixed fixture and ordering regressions must remain passing.

After this: actual HTTP lost response plus full application close/restart, real
outbox delivery/lost ACK/redelivery/old-lease fencing, concurrent cuts/jobs and
remaining time-aware reuse/QA/count/revocation guards. Task44 is not complete yet.
