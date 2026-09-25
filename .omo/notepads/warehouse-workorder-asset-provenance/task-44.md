## Task44: mixed material QA correction and numeric fixture

Goal ACTIVE. Tasks1–43 done;44 in progress;45–48/F1–F4 open.
Current checkpoint follows pushed ee60908b on work/warehouse-completion, pushed
only to origin/feat/warehouse-workorder. Keep going after this checkpoint.

The real controller+PostgreSQL fixture exposed a product bug: QA required every
planned line in bulk usage even though SERIAL deliberately uses deployment.
InventorySettlementService now binds actual receipt-backed deployment witnesses
alongside measured usage. QA records verification only; no second debit, asset or
fake bulk line. Witness equality and complete plan-line coverage are checked by SQL;
shared assignment locks hold a live reviewed installation through the QA commit.

V178.1 and178.2 are APPLIED/IMMUTABLE in owned warehouse_test.
178.1 SHA256 c4b6b169403296df9943ee7f981ef21f215f3e43712f92a02524f57088c832e3
178.2 SHA256 ff2b6f79215053a7dd6f26ca54796887268e2b5926e5300e3027d270b36f893b
V178 unchanged f6b8780d898577c16f2b63298e9d3fd43244d6e32a02a4c921ae04c7a1c9997d
The exact V178 hash is authoritative in docs/warehouse-migrations.md; do not edit it.
Next free migration178.3. SQL corrections must be forward only.

WarehouseConcurrentLifecycleIT has3 cases: full real 1,000,000MM/10ONU receipt,
100,000MM/1ONU issue+ACK,82,500MM consumption, actual customer installation+LOAN
handover,17,500MM inspected return, proof/signature/completion+independent QA;
precommit failure after actual usage posting with exact retry; and missing ONU
installation still blocks QA. Final totals917500 available MM,82500 consumed MM,
0 technician MM,9 available EA,1 active assignment/ONU,10 original assets,1 handover.
Global balance conservation stays1,000,000MM/10EA. Concurrent same-key use and return
inspection replay the exact original outcome; QA leaves all physical counts intact.
A real PostgreSQL lock observation proves assignment share-lock blocking. A test-only
public settlement decorator omits/substitutes first-approval witnesses; actual SQL
rejects the recomputed frozen body and rollback leaves0 snapshots/PENDING QA.
The original one-snapshot-per-WO guard remains. Internal corrupted owner data raises
an SQL exception through MockMVC; it is not claimed as an ordinary HTTP409 response.

Verification history (raw logs/XML private):
- r3:3 PASS after178.1, before final locking/tamper assertions.
- r4:181 tests,5 failures,0 skipped. Full report archived before reruns.
  1 cloned-snapshot test hit the existing duplicate-snapshot guard; replaced with
  actual first-approval corruption through the public owner port.
  3 isolation tests expected leaked exceptions although MaterialWorkflowErrors
  intentionally returns409 SOURCE_NOT_VERIFIED; now also verify exact-key retry.
  1 historical upgrade fixture starts CURRENT application at175.21 and fails at a
  newer transfer_receiver_id column. This remains OPEN for task46; no tests skipped.
- r5:2/3 PASS; first-approval guard correctly rejected corruption but the test
  incorrectly expected409 from WorkOrderController. Corrected to the actual SQL
  exception plus rollback assertions.
- r6: compile failure from referencing the runtime-only PostgreSQL driver class;
  corrected to java.sql.SQLException, retaining the precise guard message.
- r7:3 numeric tests PASS on final source,2m21s.
- r8:28 isolation tests PASS on final source,2m54s. Final focused total31, no
  failures/errors/skips. Proof task44/mixed-material-verification.json.

NEXT after this coherent checkpoint:
1. Fix install-before-cable and usage delta ordering. MaterialPhysicalTotalsStore
   includes DEPLOYMENT revisions, but reportUse assumes any nonzero revision means
   previous bulk usage. Query actual latest usage separately; first/delta revisions
   must advance the shared physical revision. MaterialUsagePreparation fact revision
   and PostingUsage/snapshot must agree. The current SQL owner function is privately
   captured at .omo/runtime/material-usage-current.sql; initial guard requires1 and
   delta guard requires predecessor+1. Preserve historical read/replay; add forward
   guarded SQL and validate no shared usage/deployment revision collision. Web and
   KMP initial/correction selection must use latestUsageId, not useRevision>0.
2. Serial-only jobs currently cannot create a required-material usage review; do not
   invent NONE/cable/stock. Prefer explicit immutable review of actual already-posted
   serialized deployments, positive real source witnesses and no new movement.
   Read/replay must keep current actor/location authority and preserve old hashes.
   Ordinary empty required-material usage still rejects. Test real serial-only QA,
   install-first mixed use and delta after install, plus missing/foreign/stale sources.
   Review of inherited/reassigned serial sources must be explicit, not an actor-check
   bypass. Actual deployment assertion supports legitimate ended episodes; a new review
   requires current installation, while historical immutable reads must remain valid.
3. Add WarehouseRecoveryIT: full numeric flow, real socket response loss after
   commit, whole app close/fresh context, exact original inspection replay, unchanged
   stock. @DirtiesContext(AFTER_METHOD)+ContextClosedEvent proves shutdown; existing
   WarehousePolicyITRestart has owned child SIGKILL alternative. Never kill unrelated
   processes. Use actual outbox claim/reader/delivery, drop ACK, restart/redeliver,
   fence old lease and verify one inbox/fulfillment observation. Only test lease
   scheduling metadata may be expired; no physical seed or sleep-based races.
4. Add remaining combined concurrent cuts/jobs, revocation/tenant isolation,
   stale QA/count/time-aware reuse proof. Existing task20/26/28 tests are supporting
   evidence, not a substitute for the full numeric acceptance fixture.
5. Task45 real empty-tenant browser journeys;46 full final regressions and historical
   fixture repair;47 source-matched docs/preflight;48 required CI gates;F1–F4 audits.

QA uses the shared host fd8 lock, JDK21,2 workers,Kotlin in-process,1536MiB test heap,
context cache1. Keep full reports before focused reruns. Never commit private env,
raw logs/XML/uploads/authenticated traces. Feature pushes do not deploy. No subagents
are authorized. Current functions sessions/runner status must be checked before QA.
