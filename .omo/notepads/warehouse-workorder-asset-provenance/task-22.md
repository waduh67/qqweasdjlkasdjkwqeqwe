# Task22 Execution Notes

## 2026-09-15 Incomplete Red Checkpoint

- Started at requested safe head 68dbdbd859f45bd2bfa0363fe7bee236acb3c61f; verified task21 product ancestry and matching live remote.
- Baseline ownership/strict-input/modularity XML totals 133 passing tests. Corrected task22 red suite is 9 tests, 5 failures, no errors/skips; 4 existing detach/relocation/delete controls pass.
- New CustomerAssetReplacementFixture and CustomerAssetReplacementIT are retained untracked. No production changes, migrations, commits or pushes. Task22 remains unchecked and incomplete.
- Current InventoryDeploymentService authorize rejects non-INSTALL requests at line29; WorkOrderInventoryValidationAdapter independently rejects REPLACE/REMOVE and validates PSB only. Material action mapping uses MIGRATION -> REPLACE and DISMANTLE -> REMOVE.
- Forward migration must preserve current deployment/title validators while explicitly admitting completed historical episodes in recovery; merely closing assignment/ONU conflicts with warehouse_assert_deployment_result and warehouse_assert_current_asset_title.
- The corrected fixture uses a fresh MIGRATION WO for the same customer/technician, acknowledged issued serialized replacement, and a signature attached to that WO. Removal controls create a DISMANTLE WO with its own signed evidence.
- Acknowledgement keys are tenant-wide: the first fixture incorrectly reused acknowledgement for the replacement and hit IDEMPOTENCY_CONFLICT before the behavior under test. Corrected to replacement-acknowledgement and reran.
- Current legacy topology attach/detach produces no inventory delta; real ODP relocation retained 3 topology history rows and 1 active assignment. Delete denials already return409 for the tested active detached ONU/customer.
- Corrected red failures are MALFORMED_REQUEST at replacement authorization and404 for removal routes. Successful swap, old recovery/title, immutable replacement graph and provisioning-failure durability are not yet proved.
- Missing coverage explicitly includes failure/restart outbox, exact idempotency/current authority, same-tenant wrong assignment, final-state tamper/RLS/timing, deterministic races and packaged manual QA.
- Retained DB contains about30k tenants; startup backfill costs several minutes per Spring context. Do not reset or prune inherited schema to speed tests.
- LSP rejects external-worktree paths; CodeGraph not indexed. Real compiler/Spring/PostgreSQL evidence was used.
- Owned QA stop/down completed, retained volumes/schema untouched. No production, task23/task26, RMA, UI/mobile work.
- Full status, counts, hashes and missing gates: .omo/evidence/warehouse-workorder-asset-provenance/task-22/DoneClaim.md.

## 2026-09-15 Implementation Delivered For Independent Verification

- Continuation implemented public owner APIs, AssetReplacementService/controller, immutable removal/origin and retirement records, acknowledged REPLACE authorization, quarantine recovery postings, topology replay, and durable post-commit provisioning delivery/reconciliation.
- Forward V175.80-.86 were reserved manifest-first and applied without editing V175.79 or predecessors. V175.84's first application rolled back on a missing ODP composite key; it was corrected before successful application. Applied bytes were subsequently preserved.
- Old physical identity/title/history stay intact. Recovery is QUARANTINE condition/status in TRANSIT custody, never AVAILABLE. Sold controls retain CUSTOMER; new LOAN replacement starts ISP. No task23/task26, RMA processing, UI/mobile or accounting implementation.
- Captured and fixed real reds: missing typed controller error mapping, fake delivery success without a claim, unused competing permit blocking removal, and FK lock inversion on customer delete versus swap. Customer-owned retained-history checks now reject before destructive locks.
- Scope replay fix denies removed warehouse scope as404 NOT_FOUND (existing concealment contract), while revoked actor permissions return403. Request actor/tenant/title/old-new physical authority fields fail strict decoding.
- Final exact CustomerAssetReplacementIT passed twice with48 cases. Includes independent title approval, reassignment, deletion and relocation races, app-role integrity/timing, false availability, current-authority replay and real HTTP503 failure/redelivery.
- Passing bounded batches include120 ownership+final task22,182 authorization/timezone+task22,101 lifecycle/settlement/modularity+task22,84 IAM/WorkOrder/monitoring/upgrades/modularity+task22, and172 earlier provenance/schema/ownership. Counts overlap and must not be summed as unique tests.
- Extra NetworkEndToEndIT legacy fixture still has10 failures expecting serial-only ONU registration201 where the unchanged task20 guard returns409 USE_WORKORDER_ASSET_WORKFLOW. This is recorded, not fixed or represented as passing.
- Final no-cache clean bootJar hash835414c106f30f57965d79be4b513d2225ede6e7c868ed4ca619fd340e0e5786. Packaged public HTTP swap-loan/swap-sale/remove-loan/remove-sale all passed with old availability0, correct title, retained histories, failure/restart/redelivery unchanged facts, relocation0 inventory delta and delete409.
- Final manual totals:4 removals,4 retirements,6 identities,6 assignments/2 active,6 ONUs/2 active,4 SUCCEEDED delivery rows at2 attempts,60 movement headers,72 legs,14 assignment histories,8 topology histories,1 relocation receipt.
- Seven new tables verified ENABLE/FORCE RLS; recovery view security_invoker=true. Live warehouse_app is NOSUPERUSER/NOBYPASSRLS. Owned manual schemas/processes and QA containers/network cleaned; inherited schema and both volumes retained.
-28 commits pushed immediately, author/committer fajarxfce <fajaralamsyah000@gmail.com>. Local/live remote8335ee377b1ed3c6ea4b6ed1b43af03ed2e6ec92, ahead/behind0/0. Tracked worktree clean; only this required local notepad is untracked. No .omo/evidence/runtime/secrets/build staging.
- Authoritative report: .omo/evidence/warehouse-workorder-asset-provenance/task-22/DoneClaim-final.md. Task22 remains unchecked pending independent verification; do not start task23 from this checkpoint.

## TASK22-VERIFY-01 Revision-Event Correction

- Reproduced finding at8335ee37 with5/5 red cases: unowned increments committed under normal/restored/selective timing; dismantle response1 differed from persisted retired ONU2; relocation left episode revision0.
- Added manifest-first V175.87 event storage, V175.88 revision guards/read binding, and V175.89 semantic topology timestamp comparison. V175.50 and V175.80-.86 remain unchanged.
- Opening0, exact topology event +1, retirement +1. Retirement's topology detach does not double increment. CustomerAssetRetirementStore returns SQL RETURNING revision/time, not stale installation revision. Relocation returns actual topology and episode revisions; legacy JSON replay shape remains unchanged.
- Baselines preserve old semantics and raw bytes. V86 drift remains stored but validated read/replay rejects; a valid older topology1/episode0 baseline advances to topology2/episode1 and retires at2. Legacy staged rows stay readable. No task23 attribution or task26 inspection changes.
- Packaged UTC SQL exposed JSON timestamp comparison false positives; preserved the failure, added3-case timezone red (2 failures), then fixed via new V175.89 without touching applied V175.88.
- Final correction suite30 plus upgrade1, serialization2, modularity3 and original task22 suite48 passed84/0/0/0. Exact CustomerAssetReplacementIT48 passed again afterward. Other bounded gates passed85 schema/provenance,147 ownership/revision/task22, and131 lifecycle/IAM/WorkOrder/monitoring/task22. Counts overlap.
- Populated telemetry added to swap/provisioning-failure fixtures. Barrier-driven topology/retirement race always returns the committed revision. Event increment/decrement/skip/duplicate/substitution/tenant/history mutation and all timing modes are covered.
- Final clean no-cache JAR SHA256 b062548e6601935f073e7b12d468cb100497ff7ef1d88def78af99f37c84ac1c. Packaged direct increment rejects, relocation1, swap retired response/ONU/record2, new opening0, dismantle response/ONU/record1. Three old telemetry rows and fingerprint91902e0eedf89cd145faea703f02651d survive swap/HTTP503/SIGKILL/restart/redelivery/dismantle.
- Event table FORCE RLS and7 composite tenant FKs verified; all8 final-trigger mutation sides deferred and tenant asserting. Owned schemas/processes/containers/network cleaned; ports clear and both retained volumes preserved.
-10 correction commits immediately pushed, global Gmail author/committer unchanged. Local/live remote dfa25e793d186eb8a4a0c5b96cd33e4c549edbbb, ahead/behind0/0. Tracked tree clean, only this existing notepad untracked.
- Additive report: .omo/evidence/warehouse-workorder-asset-provenance/task-22/verify-01/DoneClaim.md. Task22 remains unchecked pending independent re-verification.
